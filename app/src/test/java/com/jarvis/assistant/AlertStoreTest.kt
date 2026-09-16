package com.jarvis.assistant

import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.tools.AlarmSchedulerProvider
import com.jarvis.assistant.tools.AlertArmer
import com.jarvis.assistant.tools.AlertListRenderer
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

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

    override fun alarmsLive(): Flow<List<ScheduledAlertEntity>> =
        revision.map {
            rows.values.filter { alert -> alert.kind == ScheduledAlertEntity.KIND_ALARM }
                .sortedBy { alert -> alert.triggerAtMillis }
        }

    override suspend fun all(): List<ScheduledAlertEntity> =
        rows.values.sortedBy { alert -> alert.triggerAtMillis }

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
        assertTrue(dao.all().none { row -> row.enabled })

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
        // anchorTimeMillis must NOT be overwritten by snooze — the original
        // recurring time is preserved for onFired to compute the next day.
        assertEquals(5_000L, h.dao.byId(id)!!.anchorTimeMillis)
    }

    // ---- Snooze-drift regression test ----------------------------------------

    @Test
    fun `snoozed daily alarm does not drift after onFired`() = runBlocking {
        // Simulates: daily alarm at 07:00 (triggerAtMillis=5000), snoozed to
        // 07:10 (triggerAtMillis=66000), then fired. onFired must compute the
        // next occurrence from anchorTimeMillis (07:00) not the snoozed time.
        val h = AlertHarness(nowMillis = 5_000L)
        val id = h.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()

        // Snooze to 10 minutes later
        h.now = 6_000L
        h.scheduler.snooze(id)
        val snoozedTrigger = 6_000L + 10 * 60 * 1000L // 66_000
        assertEquals(snoozedTrigger, h.dao.byId(id)!!.triggerAtMillis)
        assertEquals(5_000L, h.dao.byId(id)!!.anchorTimeMillis)

        // The snoozed alarm fires
        h.now = snoozedTrigger
        h.scheduler.onFired(id)

        // Next occurrence must be anchored at 07:00 (5_000), not 07:10 (66_000).
        // nextDailyOccurrence(5_000, 66_000) = 5_000 + 86400000 = 86405_000
        val expectedNext = 5_000L + ALERT_DAY_MS
        assertEquals(expectedNext, h.dao.byId(id)!!.triggerAtMillis)
        assertEquals(expectedNext, h.armer.armed.getValue(id).triggerAtMillis)
    }

    // ---- Lying-switch regression: setEnabled on expired one-shots -------------

    @Test
    fun `setEnabled true on an expired one-shot ALARM rolls forward from the anchor and arms`() = runBlocking {
        // Expired one-shot clock alarm (fired on day 1, disabled by onFired);
        // the user re-enables it. The row must NEVER claim armed while
        // nothing is scheduled: the wall-clock time rolls forward from the
        // anchor to the next future occurrence BEFORE enabled=1 persists.
        val h = AlertHarness(nowMillis = 6_000L)
        val id = h.dao.insert(
            makeAlarm(trigger = 5_000L, repeatDaily = false, enabled = false),
        ).toInt()

        h.scheduler.setEnabled(id, true)

        val row = h.dao.byId(id)!!
        val expectedNext = 5_000L + ALERT_DAY_MS // anchor (5_000) + one day
        assertTrue("row must be enabled", row.enabled)
        assertEquals("trigger must roll forward from the anchor", expectedNext, row.triggerAtMillis)
        val arm = h.armer.armed.getValue(id)
        assertEquals(expectedNext, arm.triggerAtMillis)
        assertEquals(ScheduledAlertEntity.KIND_ALARM, arm.kind)
    }

    @Test
    fun `setEnabled true on an expired one-shot TIMER persists disabled and arms nothing`() = runBlocking {
        // A timer has no meaningful recurrence — re-enabling an expired one
        // must not leave enabled=1 with nothing armed.
        val h = AlertHarness(nowMillis = 6_000L)
        val id = h.dao.insert(makeTimer("чай", trigger = 5_000L, enabled = false)).toInt()

        h.scheduler.setEnabled(id, true)

        assertFalse("expired timer must stay honestly disabled", h.dao.byId(id)!!.enabled)
        assertNull("expired timer must not be armed", h.armer.armed[id])
    }

    @Test
    fun `setEnabled true on a future one-shot keeps the stored trigger and arms`() = runBlocking {
        val h = AlertHarness(nowMillis = 1_000L)
        val id = h.dao.insert(makeAlarm(trigger = 9_000L, repeatDaily = false)).toInt()

        h.scheduler.setEnabled(id, true)

        val row = h.dao.byId(id)!!
        assertTrue(row.enabled)
        assertEquals(9_000L, row.triggerAtMillis)
        assertEquals(9_000L, h.armer.armed.getValue(id).triggerAtMillis)
    }

    @Test
    fun `setEnabled false disarms and persists disabled`() = runBlocking {
        val h = AlertHarness(nowMillis = 1_000L)
        val id = h.dao.insert(makeAlarm(trigger = 9_000L, repeatDaily = true)).toInt()

        h.scheduler.setEnabled(id, false)

        assertFalse(h.dao.byId(id)!!.enabled)
        assertNull(h.armer.armed[id])
        assertTrue(h.armer.cancelled.contains(id))
    }
}

/**
 * Content-logging precedent extension (P3.4, mirrors SpeechContentLoggingTest):
 * user-set alarm labels are content — they may be logged at DEBUG ONLY. The
 * schedule() INFO line used to carry the label inside single quotes, a shape
 * LogScrubber cannot catch (rule 1 needs a `label:` key; rule 2 deliberately
 * skips single-quoted spans), so it persisted to the release file log.
 */
class AlarmContentLoggingTest {

    private class CapturingTree : Timber.Tree() {
        val entries = mutableListOf<Pair<Int, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            synchronized(entries) { entries.add(priority to message) }
        }
    }

    private companion object {
        const val PRI_DEBUG = 3
        const val PRI_INFO = 4
    }

    @Test
    fun `alarm label is logged at DEBUG only - INFO line stays content-free`() = runBlocking {
        val tree = CapturingTree()
        Timber.uprootAll()
        Timber.plant(tree)
        try {
            val h = AlertHarness(nowMillis = 10_000L)
            // makeAlarm's label ("подъём") is the user-set content sentinel.
            h.scheduler.schedule(makeAlarm(trigger = 20_000L))

            val entries = synchronized(tree.entries) { tree.entries.toList() }

            val label = "подъём"
            assertTrue(
                "an INFO+ line carried the alarm label: " +
                    entries.filter { it.first >= PRI_INFO && it.second.contains(label) },
                entries.none { it.first >= PRI_INFO && it.second.contains(label) },
            )
            assertTrue(
                "no DEBUG entry carries the label — the downgrade must keep a DEBUG carrier",
                entries.any { it.first == PRI_DEBUG && it.second.contains(label) },
            )
            assertTrue(
                "an INFO line with the content-free kind+id must still exist",
                entries.any { it.first >= PRI_INFO && it.second.contains("kind=ALARM") },
            )
        } finally {
            Timber.uprootAll()
        }
    }
}

/**
 * FIX #3 (P1-D): the @Transaction alert-state transitions must close the
 * lost-update windows of the old read-modify-write sequences — a snooze
 * racing onFired must not lose either write, and the value the scheduler
 * ARMS must be exactly the value the transaction PERSISTED (never a
 * pre-transaction snapshot). Run against the same in-memory [FakeAlertDao]
 * that inherits the DAO's real default-method bodies; Room's serialization
 * itself is device behavior, the sequencing/return-value contract is not.
 */
class AlertTransactionTest {

    @Test
    fun `applyFired reports the exact persisted trigger and keeps anchor untouched`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()

        val resolution = dao.applyFired(id, nowMillis = 6_000L) { alert ->
            alert.anchorTimeMillis + ALERT_DAY_MS
        }

        val row = dao.byId(id)!!
        val daily = resolution as com.jarvis.assistant.data.FiredResolution.DailyRearmed
        assertEquals("resolution carries the persisted trigger", row.triggerAtMillis, daily.triggerAtMillis)
        assertEquals(5_000L + ALERT_DAY_MS, row.triggerAtMillis)
        assertEquals("fired must not move the recurring anchor", 5_000L, row.anchorTimeMillis)
    }

    @Test
    fun `applyFired is idempotent in-transaction - second pass is a NoOp on a future trigger`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()
        dao.applyFired(id, 6_000L) { it.anchorTimeMillis + ALERT_DAY_MS }

        val second = dao.applyFired(id, 6_000L) { it.anchorTimeMillis + ALERT_DAY_MS }

        assertEquals(com.jarvis.assistant.data.FiredResolution.NoOp, second)
        assertEquals(5_000L + ALERT_DAY_MS, dao.byId(id)!!.triggerAtMillis)
    }

    @Test
    fun `applyFired on a one-shot disables in-transaction and reports the kind for cancel`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(makeTimer("яйца", trigger = 5_000L)).toInt()

        val resolution = dao.applyFired(id, 6_000L) { it.anchorTimeMillis + ALERT_DAY_MS }

        assertEquals(
            com.jarvis.assistant.data.FiredResolution.OneShotDisabled(ScheduledAlertEntity.KIND_TIMER),
            resolution,
        )
        assertFalse(dao.byId(id)!!.enabled)
    }

    @Test
    fun `snooze racing onFired loses neither write - final row equals final arm in both orders`() = runBlocking {
        // Order A: snooze commits first, then the fired pass runs.
        val hA = AlertHarness(nowMillis = 6_000L)
        val idA = hA.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()
        hA.scheduler.snooze(idA)
        hA.scheduler.onFired(idA)
        assertRowMatchesArm(hA, idA)

        // Order B: the fired pass commits first, then the snooze.
        val hB = AlertHarness(nowMillis = 6_000L)
        val idB = hB.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()
        hB.scheduler.onFired(idB)
        hB.scheduler.snooze(idB)
        assertRowMatchesArm(hB, idB)

        // Whichever transaction commits last fully defines the row AND the
        // armer saw that same transaction result — no torn mix of "snoozed
        // trigger in DB, next-day trigger armed" (the old lost update).
    }

    @Test
    fun `onFired arms exactly what applyFired persisted even when the row changed underneath`() = runBlocking {
        // The stale-snapshot guard: with the old byId→update→arm sequence a
        // concurrent write could make the armed trigger differ from the row.
        // Now the arm value is the transaction's own return. Assert equality
        // directly across the DAO boundary for a daily roll.
        val h = AlertHarness(nowMillis = 10_000L)
        val id = h.dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true)).toInt()

        h.scheduler.onFired(id)

        val row = h.dao.byId(id)!!
        val arm = h.armer.armed.getValue(id)
        assertEquals(row.triggerAtMillis, arm.triggerAtMillis)
        assertEquals(row.kind, arm.kind)
        assertEquals(row.label, arm.label)
    }

    @Test
    fun `applyEnable persists enabled=1 atomically with the rolled trigger`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = false, enabled = false)).toInt()

        val spec = dao.applyEnable(id) { alert -> alert.anchorTimeMillis + ALERT_DAY_MS }

        val row = dao.byId(id)!!
        assertEquals(spec!!.triggerAtMillis, row.triggerAtMillis)
        assertTrue("enable must not be able to land before the trigger — one transaction", row.enabled)
    }

    @Test
    fun `applyEnable on an expired timer persists disabled and returns null arm spec`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(makeTimer("чай", trigger = 5_000L, enabled = false)).toInt()

        val spec = dao.applyEnable(id) { alert -> if (alert.triggerAtMillis > 6_000L) alert.triggerAtMillis else null }

        assertNull(spec)
        assertFalse(dao.byId(id)!!.enabled)
    }

    @Test
    fun `applySnooze re-enables and returns the arm spec for exactly that trigger`() = runBlocking {
        val dao = FakeAlertDao()
        val id = dao.insert(makeAlarm(trigger = 5_000L, repeatDaily = true, enabled = false)).toInt()

        val spec = dao.applySnooze(id, 66_000L)

        val row = dao.byId(id)!!
        assertEquals(66_000L, spec!!.triggerAtMillis)
        assertEquals(66_000L, row.triggerAtMillis)
        assertTrue(row.enabled)
        assertEquals(5_000L, row.anchorTimeMillis) // snooze never moves the anchor
    }

    private suspend fun assertRowMatchesArm(h: AlertHarness, id: Int) {
        val row = h.dao.byId(id)!!
        val arm = h.armer.armed.getValue(id)
        assertEquals(
            "the armer must hold exactly what the DB row claims (no lying switch)",
            row.triggerAtMillis,
            arm.triggerAtMillis,
        )
    }
}

/**
 * FIX #4 (P1-D): the alarms UI / ringing lanes must share ONE scheduler +
 * armer instance. A per-call-site armer re-zeroed the one-shot degrade-note
 * gate, so the «exact alarms denied» notification re-posted on every toggle.
 * These tests pin the provider's identity contract (JVM — the real
 * SystemAlertArmer construction needs a Context; the installed-instance path
 * is context-free and is what AppGraph will use from P2).
 */
class AlarmSchedulerProviderTest {

    @Test
    fun `an installed graph-owned scheduler is returned verbatim - context never consulted`() {
        try {
            val shared = AndroidAlarmScheduler(FakeAlertDao(), FakeArmer(), { 0L })
            AlarmSchedulerProvider.install(shared)

            org.junit.Assert.assertSame(shared, AlarmSchedulerProvider.get(null))
            org.junit.Assert.assertSame(shared, AlarmSchedulerProvider.installedOrNull)
            org.junit.Assert.assertSame(
                "repeated gets must not fork instances (single degrade gate)",
                shared,
                AlarmSchedulerProvider.get(null),
            )
        } finally {
            AlarmSchedulerProvider.clearForTests()
        }
    }

    @Test
    fun `clearForTests drops the installed instance back to the unresolved state`() {
        try {
            AlarmSchedulerProvider.install(AndroidAlarmScheduler(FakeAlertDao(), FakeArmer(), { 0L }))
            org.junit.Assert.assertNotNull(AlarmSchedulerProvider.installedOrNull)

            AlarmSchedulerProvider.clearForTests()

            org.junit.Assert.assertNull(AlarmSchedulerProvider.installedOrNull)
        } finally {
            AlarmSchedulerProvider.clearForTests()
        }
    }

    @Test
    fun `get without an installed instance or context fails fast instead of silently forking`() {
        AlarmSchedulerProvider.clearForTests()
        try {
            AlarmSchedulerProvider.get(null)
            org.junit.Assert.fail("expected IllegalArgumentException: a Context is required pre-P2-wiring")
        } catch (expected: IllegalArgumentException) {
            // Honest seam: building the fallback needs an app context; a
            // silent second armer is exactly the bug this lane is fixing.
        }
    }
}
