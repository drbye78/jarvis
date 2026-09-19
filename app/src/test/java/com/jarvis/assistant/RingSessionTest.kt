package com.jarvis.assistant

import com.jarvis.assistant.data.AlertDao
import com.jarvis.assistant.data.RingSessionDao
import com.jarvis.assistant.data.RingSessionEntity
import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.tools.AlertArmer
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import com.jarvis.assistant.tools.RingBeginOutcome
import com.jarvis.assistant.tools.RingCoordinator
import com.jarvis.assistant.tools.RingPresenter
import com.jarvis.assistant.tools.RingSessionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REMEDIATION_PLAN P3.1/P3.2/P3.5: the ring lifecycle is durable ring state in
 * `ring_sessions`, not a process-local field. These are the JVM tests for
 * ring-session CAPTURE at ring-begin and RESUME/validation afterwards — the
 * Room DAO itself needs a device, but the coordinator's sequencing and the
 * pure token policy do not.
 */
class RingSessionTest {

    // ---- Pure token policy ---------------------------------------------------

    @Test
    fun `isLive requires a non-empty matching token`() {
        val session = session(alertId = 7, token = "tok-a")

        assertTrue(RingSessionPolicy.isLive(session, "tok-a"))
        assertFalse("a different token is stale", RingSessionPolicy.isLive(session, "tok-b"))
        assertFalse("no token is not a match", RingSessionPolicy.isLive(session, null))
        assertFalse(RingSessionPolicy.isLive(null, "tok-a"))
    }

    @Test
    fun `authorizes enforces a supplied token but allows the token-less show-intent path`() {
        val session = session(alertId = 7, token = "tok-a")

        assertTrue(RingSessionPolicy.authorizes(session, "tok-a"))
        assertFalse(RingSessionPolicy.authorizes(session, "tok-b"))
        // The system alarm-clock show-intent is built at ARM time, before the
        // ring token exists — its dismiss must still work on a live session.
        assertTrue(RingSessionPolicy.authorizes(session, null))
        assertFalse(RingSessionPolicy.authorizes(null, null))
    }

    // ---- RingCoordinator: capture / resume / token guard ---------------------

    @Test
    fun `beginRing captures a durable session, fires the terminal transition and presents`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        val id = f.scheduleTimer(trigger = 5_000L)

        val outcome = f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 5_000L)

        val ringing = outcome as RingBeginOutcome.Ringing
        assertEquals("tok-1", ringing.session.token)
        assertEquals(1_000L, ringing.session.startedAtElapsed)
        assertEquals(5_000L, ringing.session.firedTriggerMillis)
        assertEquals(id, f.ringDao.byAlertId(id)?.alertId)
        assertEquals("tok-1", f.ringDao.byAlertId(id)?.token)
        // Terminal transition ran BEFORE presenting (one-shot disabled).
        assertFalse(f.alertDao.byId(id)!!.enabled)
        assertTrue("presenter must have been asked to present", f.presenter.presented.contains("tok-1"))
    }

    @Test
    fun `a second ring for the same alert replaces the session token - resume sees the new one`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        val id = f.scheduleTimer(trigger = 5_000L)

        f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 5_000L)
        // Re-enable + fire again (e.g. re-scheduled, then rings once more).
        f.alertDao.setEnabled(id, true)
        f.alertDao.update(f.alertDao.byId(id)!!.copy(triggerAtMillis = 9_000L))
        f.newTokens.add("tok-2")
        val second = f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 9_000L)

        val ringing = second as RingBeginOutcome.Ringing
        assertEquals("tok-2", ringing.session.token)
        assertTrue(f.coordinator.isLive(id, "tok-2"))
        assertFalse("the previous ring token is stale now", f.coordinator.isLive(id, "tok-1"))
        // REPLACE on the PK: exactly one durable session per alert.
        assertEquals(1, f.ringDao.all().size)
    }

    @Test
    fun `beginRing suppresses a stale fire instead of ringing`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        // A row that is already disabled (its occurrence was consumed) makes
        // applyFired a NoOp — the ring must not start for it.
        val id = f.alertDao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = "старый",
                triggerAtMillis = 5_000L,
                repeatDaily = false,
                enabled = false,
            ),
        ).toInt()

        val outcome = f.coordinator.beginRing(id, "старый", isTimer = true, firedTriggerMillis = 5_000L)

        assertEquals(RingBeginOutcome.Suppressed, outcome)
        assertNull("no session may be captured for a suppressed ring", f.ringDao.byAlertId(id))
        assertTrue("nothing may be presented for a suppressed ring", f.presenter.presented.isEmpty())
    }

    @Test
    fun `dismiss with a stale token is ignored and the live ring survives`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        val id = f.scheduleTimer(trigger = 5_000L)
        f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 5_000L)

        val acted = f.coordinator.dismiss(id, "stale-token")

        assertFalse(acted)
        assertTrue("the live session must remain", f.coordinator.isLive(id, "tok-1"))
        assertTrue("the ringer must NOT have been retired", f.presenter.retired.isEmpty())
    }

    @Test
    fun `dismiss with the matching token retires the presenter and deletes the session`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        val id = f.scheduleTimer(trigger = 5_000L)
        f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 5_000L)

        val acted = f.coordinator.dismiss(id, "tok-1")

        assertTrue(acted)
        assertTrue(f.presenter.retired.contains(id))
        assertNull(f.ringDao.byAlertId(id))
    }

    @Test
    fun `snooze with the matching token retires the ring and moves the trigger forward`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        val id = f.scheduleTimer(trigger = 5_000L)
        f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 5_000L)

        val acted = f.coordinator.snooze(id, "tok-1", delayMillis = 600_000L)

        assertTrue(acted)
        assertNull("the old ring session is ended", f.ringDao.byAlertId(id))
        assertTrue("the alert is re-armed into the future", f.alertDao.byId(id)!!.enabled)
    }

    @Test
    fun `endLiveRing stops a ring unconditionally for the cancel path`() = runBlocking {
        val f = Fixture(nowElapsed = 1_000L)
        val id = f.scheduleTimer(trigger = 5_000L)
        f.coordinator.beginRing(id, "яйца", isTimer = true, firedTriggerMillis = 5_000L)

        f.coordinator.endLiveRing(id)

        assertTrue(f.presenter.retired.contains(id))
        assertNull(f.ringDao.byAlertId(id))
    }

    private fun session(alertId: Int, token: String) = RingSessionEntity(
        alertId = alertId,
        token = token,
        startedAtElapsed = 0L,
        label = "x",
        isTimer = false,
        firedTriggerMillis = 0L,
    )

    // ---- Fakes ---------------------------------------------------------------

    private class Fixture(val nowElapsed: Long) {
        val alertDao = FakeAlertDao()
        val ringDao = FakeRingSessionDao()
        val armer = FakeArmer()
        val presenter = RecordingPresenter()
        var tokenSeq = 1
        val newTokens = ArrayDeque<String>()

        val coordinator = RingCoordinator(
            ringDao = ringDao,
            scheduler = AndroidAlarmScheduler(alertDao, armer, { 0L }),
            presenter = presenter,
            nowElapsed = { nowElapsed },
            newToken = { newTokens.removeFirstOrNull() ?: "tok-${tokenSeq++}" },
        )

        suspend fun scheduleTimer(trigger: Long): Int = alertDao.insert(
            ScheduledAlertEntity(
                kind = ScheduledAlertEntity.KIND_TIMER,
                label = "яйца",
                triggerAtMillis = trigger,
                repeatDaily = false,
            ),
        ).toInt()
    }

    private class FakeRingSessionDao : RingSessionDao {
        private val rows = LinkedHashMap<Int, RingSessionEntity>()

        override suspend fun upsert(session: RingSessionEntity) {
            rows[session.alertId] = session
        }

        override suspend fun byAlertId(alertId: Int): RingSessionEntity? = rows[alertId]

        override suspend fun all(): List<RingSessionEntity> = rows.values.toList()

        override suspend fun delete(alertId: Int) {
            rows.remove(alertId)
        }

        override suspend fun wipeAll() {
            rows.clear()
        }
    }

    private class RecordingPresenter : RingPresenter {
        val presented = mutableListOf<String>()
        val retired = mutableListOf<Int>()

        override fun fullScreenAllowed(): Boolean = true

        override fun notificationsEnabled(): Boolean = true

        override fun present(session: RingSessionEntity, fullScreenAllowed: Boolean): Boolean {
            presented.add(session.token)
            return true
        }

        override fun retire(alertId: Int) {
            retired.add(alertId)
        }
    }

    private class FakeArmer : AlertArmer {
        val armed = LinkedHashMap<Int, Long>()

        override fun arm(alert: ScheduledAlertEntity) {
            armed[alert.id] = alert.triggerAtMillis
        }

        override fun cancel(id: Int, kind: String) {
            armed.remove(id)
        }
    }

    private class FakeAlertDao : AlertDao {
        private val rows = LinkedHashMap<Int, ScheduledAlertEntity>()
        private var nextId = 1

        override suspend fun insert(alert: ScheduledAlertEntity): Long {
            val id = nextId++
            rows[id] = alert.copy(id = id)
            return id.toLong()
        }

        override suspend fun update(alert: ScheduledAlertEntity) {
            rows[alert.id] = alert
        }

        override suspend fun byId(id: Int): ScheduledAlertEntity? = rows[id]

        override fun alarmsLive(): Flow<List<ScheduledAlertEntity>> = flowOf(emptyList())

        override suspend fun all(): List<ScheduledAlertEntity> = rows.values.toList()

        override suspend fun delete(id: Int) {
            rows.remove(id)
        }

        override suspend fun setEnabled(id: Int, enabled: Boolean) {
            val current = rows[id] ?: return
            rows[id] = current.copy(enabled = enabled)
        }
    }
}
