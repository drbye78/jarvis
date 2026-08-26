package com.jarvis.assistant

import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.tools.AlertArmer
import com.jarvis.assistant.tools.AlertListRenderer
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 unified alert store (PLAN.md §3.3): DAO round-trip semantics, boot
 * re-arm policy, request-code uniqueness, onFired idempotency, voice listing
 * incl. disabled alerts, and timer persistence — all on the JVM against an
 * in-memory [AlertDao] fake and a recording [AlertArmer] fake (no
 * instrumentation; Room itself needs a device).
 */
private const val ALERT_DAY_MS = 24 * 60 * 60 * 1000L

/** In-memory AlertDao fake mirroring the Room contract (autoincrement ids). */
private class FakeAlertDao : AlertDao {
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
        revision.map { rows.values.sortedBy { alert -> alert.triggerAtMillis } }

    override fun alarmsLive(): Flow<List<ScheduledAlertEntity>> =
        revision.map {
            rows.values.filter { alert -> alert.kind == ScheduledAlertEntity.KIND_ALARM }
                .sortedBy { alert -> alert.triggerAtMillis }
        }

    override suspend fun all(): List<ScheduledAlertEntity> =
        rows.values.sortedBy { alert -> alert.triggerAtMillis }

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

/** Recording armer fake: keyed by request code, cancel removes the arm. */
private class FakeArmer : AlertArmer {
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

private class AlertHarness(nowMillis: Long) {
    val dao = FakeAlertDao()
    val armer = FakeArmer()
    var now: Long = nowMillis
    val scheduler = AndroidAlarmScheduler(dao, armer, { now })
}

private fun makeAlarm(trigger: Long, repeatDaily: Boolean = false, enabled: Boolean = true) =
    ScheduledAlertEntity(
        kind = ScheduledAlertEntity.KIND_ALARM,
        label = "подъём",
        triggerAtMillis = trigger,
        repeatDaily = repeatDaily,
        enabled = enabled,
    )

private fun makeTimer(label: String, trigger: Long, enabled: Boolean = true) =
    ScheduledAlertEntity(
        kind = ScheduledAlertEntity.KIND_TIMER,
        label = label,
        triggerAtMillis = trigger,
        repeatDaily = false,
        enabled = enabled,
    )

class AlertStoreTest {

    // ---- Item 1 (schema/store): DAO round-trip -----------------------------

    @Test
    fun `dao round trip insert query update disable delete`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_ALARM,
                label = "подъём",
                triggerAtMillis = 1_000L,
                repeatDaily = true,
            ),
        ).toInt()

        val loaded = dao.byId(id)!!
        assertEquals(ScheduledAlertEntity.KIND_ALARM, loaded.kind)
        assertEquals("подъём", loaded.label)
        assertEquals(1_000L, loaded.triggerAtMillis)
        assertTrue(loaded.repeatDaily)
        assertTrue(loaded.enabled)

        dao.update(loaded.copy(label = "работа"))
        assertEquals("работа", dao.byId(id)!!.label)

        dao.setEnabled(id, false)
        assertFalse(dao.byId(id)!!.enabled)
        assertTrue(dao.enabled().isEmpty())

        dao.delete(id)
        assertNull(dao.byId(id))
    }

    // ---- Items 2/6 (boot re-arm policy) ------------------------------------

    @Test
    fun `rescheduleAllOnBoot arms alarms and only future timers`() = runBlocking {
        val h = AlertHarness(nowMillis = 10_000L)
        val dailyPastId = h.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()
        val futureTimerId = h.dao.insert(makeTimer("чай", trigger = 12_000L)).toInt()
        val expiredTimerId = h.dao.insert(makeTimer("старый", trigger = 9_000L)).toInt()
        val disabledAlarmId = h.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true, enabled = false)).toInt()

        h.scheduler.rescheduleAllOnBoot()

        // Daily rolled forward past 'now' to its next occurrence.
        assertEquals(5_000L + ALERT_DAY_MS, h.armer.armed.getValue(dailyPastId).triggerAtMillis)
        // Future timer armed as-is.
        assertEquals(12_000L, h.armer.armed.getValue(futureTimerId).triggerAtMillis)
        assertEquals(ScheduledAlertEntity.KIND_TIMER, h.armer.armed.getValue(futureTimerId).kind)
        // Expired timer skipped AND disabled so it never lingers armed-able.
        assertNull(h.armer.armed[expiredTimerId])
        assertFalse(h.dao.byId(expiredTimerId)!!.enabled)
        // Disabled rows stay disarmed.
        assertNull(h.armer.armed[disabledAlarmId])
    }

    // ---- Item 1 (request-code authority): uniqueness property --------------

    @Test
    fun `N scheduled alerts yield N distinct request codes equal to row ids`() = runBlocking {
        val h = AlertHarness(nowMillis = 0L)
        val n = 50
        val ids = mutableListOf<Int>()
        repeat(n) { i ->
            val entity = if (i % 2 == 0) {
                makeAlarm(trigger = 100_000L + i, repeatDaily = i % 4 == 0)
            } else {
                makeTimer("t$i", trigger = 200_000L + i)
            }
            ids.add(h.scheduler.schedule(entity).id)
        }
        assertEquals(n, h.armer.armed.size)
        assertEquals(ids.toSet(), h.armer.armed.keys.toSet())
        ids.forEach { id ->
            assertEquals(id, h.armer.armed.getValue(id).id)
            assertEquals(id, h.dao.byId(id)!!.id)
        }
    }

    // ---- Item 4 (M3): onFired idempotency -----------------------------------

    @Test
    fun `onFired twice for a daily alarm arms exactly one next occurrence`() = runBlocking {
        val h = AlertHarness(nowMillis = 10_000L)
        val id = h.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()
        h.armer.arm(id, 5_000L, ScheduledAlertEntity.KIND_ALARM, "подъём")

        h.now = 6_000L // alarm fired at 5000, activity opens at 6000
        h.scheduler.onFired(id)

        val firstArm = h.armer.armed.getValue(id)
        assertEquals(5_000L + ALERT_DAY_MS, firstArm.triggerAtMillis)
        assertEquals(5_000L + ALERT_DAY_MS, h.dao.byId(id)!!.triggerAtMillis)

        val snapshot = h.armer.armed.toMap()
        h.scheduler.onFired(id) // e.g. activity recreated / second lifecycle pass

        assertEquals(snapshot, h.armer.armed) // still exactly ONE next occurrence
        assertEquals(5_000L + ALERT_DAY_MS, h.dao.byId(id)!!.triggerAtMillis)
    }

    @Test
    fun `onFired disables a one-shot timer row and is a no-op when repeated`() = runBlocking {
        val h = AlertHarness(nowMillis = 10_000L)
        val id = h.dao.insert(makeTimer("яйца", trigger = 5_000L)).toInt()
        h.armer.arm(id, 5_000L, ScheduledAlertEntity.KIND_TIMER, "яйца")

        h.now = 6_000L
        h.scheduler.onFired(id)

        assertFalse(h.dao.byId(id)!!.enabled)
        assertNull(h.armer.armed[id])
        assertTrue(h.armer.cancelled.contains(id))

        h.scheduler.onFired(id) // idempotent: stays disabled, nothing re-armed
        assertFalse(h.dao.byId(id)!!.enabled)
        assertNull(h.armer.armed[id])
    }

    // ---- Item 3 (m16): listing includes disabled alerts with status ---------

    @Test
    fun `listAlarms output includes disabled alerts with status field`() {
        // Anchored to LOCAL midnight so the renderer's Calendar-based HH:mm
        // output matches regardless of the JVM's default timezone.
        val localMidnight = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val enabled = makeAlarm(trigger = localMidnight + 7 * 3_600_000L, repeatDaily = true) // 07:00
        val disabled = makeTimer("чай", trigger = localMidnight + 9 * 3_600_000L, enabled = false) // 09:00

        val parsed = Json.parseToJsonElement(AlertListRenderer.render(listOf(enabled, disabled))).jsonArray

        assertEquals(2, parsed.size)
        val first = parsed[0].jsonObject
        assertEquals("enabled", first["status"]!!.jsonPrimitive.content)
        assertTrue(first["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("ALARM", first["kind"]!!.jsonPrimitive.content)
        assertEquals("07:00", first["time"]!!.jsonPrimitive.content)

        val second = parsed[1].jsonObject
        assertEquals("disabled", second["status"]!!.jsonPrimitive.content)
        assertFalse(second["enabled"]!!.jsonPrimitive.boolean)
        assertEquals("TIMER", second["kind"]!!.jsonPrimitive.content)
        assertEquals("09:00", second["time"]!!.jsonPrimitive.content)
    }

    // ---- Item 3 (S3): setTimer persists a row AND schedules -----------------

    @Test
    fun `setTimer persists a row and arms the scheduler`() = runBlocking {
        val h = AlertHarness(nowMillis = 100_000L)

        val output = Json.parseToJsonElement(
            com.jarvis.assistant.tools.SetTimerTool(h.scheduler)
                .execute("""{"minutes":5,"label":"чай"}"""),
        ).jsonObject

        assertEquals("started", output["status"]!!.jsonPrimitive.content)
        val timerId = output["timer_id"]!!.jsonPrimitive.content.toInt()

        val rows = h.dao.all()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(timerId, row.id)
        assertEquals(ScheduledAlertEntity.KIND_TIMER, row.kind)
        assertEquals("чай", row.label)
        assertEquals(400_000L, row.triggerAtMillis) // now + 5 min
        assertTrue(row.enabled)
        assertFalse(row.repeatDaily)

        val arm = h.armer.armed.getValue(timerId)
        assertEquals(400_000L, arm.triggerAtMillis)
        assertEquals(ScheduledAlertEntity.KIND_TIMER, arm.kind)
    }

    // ---- Item 4 support: snooze delegates through the scheduler -------------

    @Test
    fun `snooze moves the ring into the future under the same request code`() = runBlocking {
        val h = AlertHarness(nowMillis = 6_000L)
        val id = h.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()

        h.scheduler.snooze(id)

        val expected = 6_000L + AndroidAlarmScheduler.DEFAULT_SNOOZE_MS
        assertEquals(expected, h.dao.byId(id)!!.triggerAtMillis)
        assertEquals(expected, h.armer.armed.getValue(id).triggerAtMillis)
        assertEquals(id, h.armer.armed.getValue(id).id)
    }
}
