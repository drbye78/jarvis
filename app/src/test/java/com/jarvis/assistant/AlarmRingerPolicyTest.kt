package com.jarvis.assistant

import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.tools.AlarmRingerPolicy
import com.jarvis.assistant.tools.AlertArmer
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REMEDIATION_PLAN P1.5: [AlarmRingerPolicy] decision math + the BootReceiver
 * reboot re-schedule path.
 *
 * Honest split:
 * - Covered here: the ringer's extracted decision math (auto-stop threshold,
 *   vibration waveform + repeat index) and AndroidAlarmScheduler
 *   .rescheduleAllOnBoot() — exactly what service/BootReceiver.onReceive
 *   invokes for ACTION_BOOT_COMPLETED / ACTION_MY_PACKAGE_REPLACED.
 * - Device-only (not covered, by nature): AlarmRinger's MediaPlayer prepare/
 *   start/stop, RingtoneManager URI resolution, Vibrator vibrate/cancel and
 *   the prepare-vs-stop race inside start(); and BootReceiver's Context-bound
 *   action routing (AppPrefs writes, postActivationPrompt).
 */
class AlarmRingerPolicyTest {

    @Test
    fun `auto-stop threshold is five minutes - a missed alarm cannot ring forever`() {
        assertEquals(5 * 60 * 1000L, AlarmRingerPolicy.DEFAULT_MAX_DURATION_MS)
    }

    @Test
    fun `auto-stop delay equals the requested budget`() {
        assertEquals(5 * 60 * 1000L, AlarmRingerPolicy.autoStopDelayMs())
        assertEquals(1_000L, AlarmRingerPolicy.autoStopDelayMs(1_000L))
    }

    @Test
    fun `non-positive auto-stop budgets clamp to zero instead of hanging`() {
        assertEquals(0L, AlarmRingerPolicy.autoStopDelayMs(0L))
        assertEquals(0L, AlarmRingerPolicy.autoStopDelayMs(-1L))
    }

    @Test
    fun `vibration pattern is off-vibrate-pause waveform repeated from the start`() {
        val pattern = AlarmRingerPolicy.vibrationPattern()
        assertEquals(3, pattern.size)
        assertEquals(0L, pattern[0]) // no leading delay
        assertEquals(500L, pattern[1]) // vibrate
        assertEquals(500L, pattern[2]) // pause
        assertEquals(0, AlarmRingerPolicy.VIBRATION_REPEAT_INDEX)
    }
}

// ---------------------------------------------------------------------------
// BootReceiver re-schedule path: the receiver's only re-schedule duty is
// AndroidAlarmScheduler(context, dao).rescheduleAllOnBoot(). These tests pin
// WHICH alerts survive a reboot and HOW they are re-armed, through the same
// seam the receiver uses.
// ---------------------------------------------------------------------------

/** In-memory AlertDao fake (file-local — no name clash with other files' fakes). */
private class BootFakeAlertDao : AlertDao {
    private val rows = LinkedHashMap<Int, ScheduledAlertEntity>()
    private var nextId = 1
    private val revision = MutableStateFlow(0)

    private fun bump() {
        revision.value += 1
    }

    override suspend fun insert(alert: ScheduledAlertEntity): Long {
        val id = nextId++
        rows[id] = alert.copy(id = id)
        bump()
        return id.toLong()
    }

    override suspend fun update(alert: ScheduledAlertEntity) {
        rows[alert.id] = alert
        bump()
    }

    override suspend fun byId(id: Int): ScheduledAlertEntity? = rows[id]

    override fun allLive(): Flow<List<ScheduledAlertEntity>> =
        revision.map { rows.values.sortedBy { a -> a.triggerAtMillis } }

    override fun alarmsLive(): Flow<List<ScheduledAlertEntity>> =
        revision.map {
            rows.values.filter { a -> a.kind == ScheduledAlertEntity.KIND_ALARM }
                .sortedBy { a -> a.triggerAtMillis }
        }

    override suspend fun all(): List<ScheduledAlertEntity> =
        rows.values.sortedBy { a -> a.triggerAtMillis }

    override suspend fun enabled(): List<ScheduledAlertEntity> =
        rows.values.filter { it.enabled }

    override suspend fun delete(id: Int) {
        rows.remove(id)
        bump()
    }

    override suspend fun setEnabled(id: Int, enabled: Boolean) {
        val current = rows[id] ?: return
        rows[id] = current.copy(enabled = enabled)
        bump()
    }
}

/** Recording armer fake keyed by request code; cancel removes the arm. */
private class BootRecordingArmer : AlertArmer {
    data class Armed(val id: Int, val triggerAtMillis: Long, val kind: String, val label: String)

    val armed = LinkedHashMap<Int, Armed>()
    val cancelled = mutableListOf<Int>()

    override fun arm(id: Int, triggerAtMillis: Long, kind: String, label: String) {
        armed[id] = Armed(id, triggerAtMillis, kind, label)
    }

    override fun cancel(id: Int, kind: String) {
        armed.remove(id)
        cancelled.add(id)
    }
}

private class BootHarness(startNow: Long) {
    val dao = BootFakeAlertDao()
    val armer = BootRecordingArmer()
    var now: Long = startNow
    val scheduler = AndroidAlarmScheduler(dao, armer, { now })
}

class BootRescheduleTest {

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `boot re-arms daily alarms rolled past missed days and future timers only`() = runBlocking {
        val h = BootHarness(startNow = 10_000L)
        val dailyPast = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = "подъём",
                triggerAtMillis = 5_000L,
                repeatDaily = true,
            ),
        ).toInt()
        val futureTimer = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = "чай",
                triggerAtMillis = 12_000L,
            ),
        ).toInt()
        val expiredTimer = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = "старый",
                triggerAtMillis = 9_999L,
            ),
        ).toInt()
        val disabledAlarm = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = "выключен",
                triggerAtMillis = 5_000L,
                repeatDaily = true,
                enabled = false,
            ),
        ).toInt()

        h.scheduler.rescheduleAllOnBoot()

        // Daily alarm: re-armed ALWAYS, rolled to the next occurrence past now.
        val dailyArm = h.armer.armed.getValue(dailyPast)
        assertEquals(5_000L + day, dailyArm.triggerAtMillis)
        assertEquals(5_000L + day, h.dao.byId(dailyPast)!!.triggerAtMillis)
        assertEquals(ScheduledAlertEntity.KIND_ALARM, dailyArm.kind)
        // Future timer: re-armed as-is — timers survive reboot.
        assertEquals(12_000L, h.armer.armed.getValue(futureTimer).triggerAtMillis)
        assertEquals(ScheduledAlertEntity.KIND_TIMER, h.armer.armed.getValue(futureTimer).kind)
        // Expired timer: NOT re-armed and DISABLED, so it can't linger as an
        // armed-able ghost for a future boot.
        assertNull(h.armer.armed[expiredTimer])
        assertFalse(h.dao.byId(expiredTimer)!!.enabled)
        // Disabled rows stay untouched (never resurrected by a reboot).
        assertNull(h.armer.armed[disabledAlarm])
        assertFalse(h.dao.byId(disabledAlarm)!!.enabled)
    }

    @Test
    fun `boot re-arm is idempotent - a second pass does not double-roll dailies`() = runBlocking {
        val h = BootHarness(startNow = 10_000L)
        val daily = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = "подъём",
                triggerAtMillis = 5_000L,
                repeatDaily = true,
            ),
        ).toInt()

        h.scheduler.rescheduleAllOnBoot()
        val first = h.armer.armed.toMap()
        assertEquals(5_000L + day, first.getValue(daily).triggerAtMillis)

        h.scheduler.rescheduleAllOnBoot()

        // The daily trigger is already in the future: rolled exactly once.
        assertEquals(5_000L + day, h.dao.byId(daily)!!.triggerAtMillis)
        assertEquals(5_000L + day, h.armer.armed.getValue(daily).triggerAtMillis)
    }

    @Test
    fun `snoozed daily alarm re-arms from the anchor time on boot - no drift`() = runBlocking {
        // BootReceiver runs after a snooze moved triggerAtMillis (07:00 snoozed
        // to 07:10): the reboot must NOT bake the snoozed time in as the new
        // daily anchor — the next occurrence computes from anchorTimeMillis.
        val h = BootHarness(startNow = 5_000L)
        val id = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = "подъём",
                triggerAtMillis = 5_000L,
                repeatDaily = true,
            ),
        ).toInt()
        h.now = 6_000L
        h.scheduler.snooze(id)
        val snoozedTrigger = 6_000L + AndroidAlarmScheduler.DEFAULT_SNOOZE_MS
        assertEquals(snoozedTrigger, h.dao.byId(id)!!.triggerAtMillis)

        h.scheduler.rescheduleAllOnBoot() // reboot happens while snoozed

        // Rolled from the ANCHOR (5_000), not the snoozed trigger.
        assertEquals(5_000L + day, h.dao.byId(id)!!.triggerAtMillis)
        assertEquals(5_000L + day, h.armer.armed.getValue(id).triggerAtMillis)
    }

    @Test
    fun `timer exactly due at boot time counts as expired and is disabled`() = runBlocking {
        // trigger <= now is the one-shot expiry rule — an at-the-boundary
        // timer must not linger armed-able (the platform alarm never fired
        // for it; rebooting into its trigger time means it is lost).
        val h = BootHarness(startNow = 10_000L)
        val dueNow = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = "точно сейчас",
                triggerAtMillis = 10_000L,
            ),
        ).toInt()

        h.scheduler.rescheduleAllOnBoot()

        assertNull(h.armer.armed[dueNow])
        assertFalse(h.dao.byId(dueNow)!!.enabled)
    }

    @Test
    fun `future daily anchor is armed as-is without being rolled`() = runBlocking {
        // A daily alert whose anchor (and trigger) is still in the future —
        // scheduled moments before the reboot — keeps its exact trigger.
        val h = BootHarness(startNow = 10_000L)
        val id = h.dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = "завтра",
                triggerAtMillis = 10_000L + day,
                repeatDaily = true,
            ),
        ).toInt()

        h.scheduler.rescheduleAllOnBoot()

        assertEquals(10_000L + day, h.armer.armed.getValue(id).triggerAtMillis)
        assertTrue(h.dao.byId(id)!!.enabled)
    }
}
