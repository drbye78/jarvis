package com.jarvis.assistant

import com.jarvis.assistant.data.ScheduledAlertEntity
import com.jarvis.assistant.tools.ExactAlarmPolicy
import com.jarvis.assistant.tools.ExactAlarmPolicy.AlertDeliveryPlan
import com.jarvis.assistant.tools.ExactAlarmPolicy.DegradeReason
import com.jarvis.assistant.tools.OneShotGate
import com.jarvis.assistant.tools.OverdueAlertPolicy
import com.jarvis.assistant.tools.OverdueAlertPolicy.OverdueDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REMEDIATION_PLAN P3.1: the exact-alarm delivery-plan matrix. Pure JVM —
 * [sdkInt] and the `canScheduleExactAlarms()` result are passed in as plain
 * values (no Robolectric; mirrors the ServicePolicy/AlarmRingerPolicy
 * extraction style, commit e07a93c).
 *
 * Android-only parts NOT covered here (by nature): the `setAlarmClock` /
 * `setWindow` / `setAndAllowWhileIdle` AlarmManager calls themselves, the
 * notification channel/note plumbing in SystemAlertArmer and the manifest
 * permission grant behavior.
 */
class ExactAlarmPolicyTest {

    // ---- planFor: per-kind exact-delivery plan (timer/alarm) ----

    @Test
    fun `planFor timer - API 29 30 declares the normal permission, exact`() {
        assertEquals(
            AlertDeliveryPlan.ExactAllowWhileIdle,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 29, null),
        )
        assertEquals(
            AlertDeliveryPlan.ExactAllowWhileIdle,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 30, null),
        )
    }

    @Test
    fun `planFor timer - API 31+ granted is exact, revoked degrades`() {
        assertEquals(
            AlertDeliveryPlan.ExactAllowWhileIdle,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 31, true),
        )
        assertEquals(
            AlertDeliveryPlan.ExactAllowWhileIdle,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 34, true),
        )
        assertEquals(
            AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED),
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 31, false),
        )
        assertEquals(
            AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED),
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 34, false),
        )
    }

    @Test
    fun `planFor alarm - API 29 30 declares the normal permission, alarm clock`() {
        assertEquals(
            AlertDeliveryPlan.AlarmClock,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 29, null),
        )
        assertEquals(
            AlertDeliveryPlan.AlarmClock,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 30, null),
        )
    }

    @Test
    fun `planFor alarm - API 31+ granted is alarm clock, revoked degrades`() {
        assertEquals(
            AlertDeliveryPlan.AlarmClock,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 31, true),
        )
        assertEquals(
            AlertDeliveryPlan.AlarmClock,
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 34, true),
        )
        assertEquals(
            AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED),
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 31, false),
        )
        assertEquals(
            AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED),
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 34, false),
        )
    }

    /** Defensive: a must-not-crash "unknown" (never true) cannot fire an exact path. */
    @Test
    fun `planFor API 31+ with null (queried but unavailable) degrades both kinds`() {
        assertEquals(
            AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED),
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_TIMER, 31, null),
        )
        assertEquals(
            AlertDeliveryPlan.Degraded(DegradeReason.PERMISSION_DENIED),
            ExactAlarmPolicy.planFor(ScheduledAlertEntity.KIND_ALARM, 31, null),
        )
    }

    // ---- OverdueAlertPolicy: ring / roll-forward / disable table ----

    @Test
    fun `overdue within the grace window rings - both kinds`() {
        assertEquals(
            OverdueDecision.Ring,
            OverdueAlertPolicy.decide(0L, ScheduledAlertEntity.KIND_ALARM, repeatDaily = false),
        )
        assertEquals(
            OverdueDecision.Ring,
            OverdueAlertPolicy.decide(15 * 60 * 1000L, ScheduledAlertEntity.KIND_ALARM, repeatDaily = false),
        )
        assertEquals(
            OverdueDecision.Ring,
            OverdueAlertPolicy.decide(1L, ScheduledAlertEntity.KIND_TIMER, repeatDaily = false),
        )
    }

    @Test
    fun `one-shot alarm beyond grace rolls forward`() {
        assertEquals(
            OverdueDecision.RollForward,
            OverdueAlertPolicy.decide(15 * 60 * 1000L + 1, ScheduledAlertEntity.KIND_ALARM, repeatDaily = false),
        )
    }

    @Test
    fun `timer beyond grace disables with notice`() {
        assertEquals(
            OverdueDecision.DisableWithNotice,
            OverdueAlertPolicy.decide(30 * 60 * 1000L, ScheduledAlertEntity.KIND_TIMER, repeatDaily = false),
        )
    }

    @Test
    fun `daily alarm beyond grace rings - it still has a future occurrence`() {
        assertEquals(
            OverdueDecision.Ring,
            OverdueAlertPolicy.decide(30 * 60 * 1000L, ScheduledAlertEntity.KIND_ALARM, repeatDaily = true),
        )
    }

    @Test
    fun `unknown kind beyond grace falls back to ring`() {
        assertEquals(
            OverdueDecision.Ring,
            OverdueAlertPolicy.decide(60 * 60 * 1000L, "REMINDER", repeatDaily = false),
        )
    }

    @Test
    fun `degraded window is bounded and finite`() {
        val window = ExactAlarmPolicy.INEXACT_WINDOW_MS
        assertTrue("window must be positive, was $window", window in 1..24 * 60 * 60_000L)
        assertEquals(10 * 60 * 1000L, window)
    }

    // ---- FIX #5/#4 (P1-D): the degrade note's payload + single-fire gate ----

    @Test
    fun `the grant-screen deep-link is offered on API 31+ only`() {
        // Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM exists from 31: below
        // that the permission is a normal grant and there is nothing to open,
        // so a contentIntent there would be a dead tap.
        assertFalse(ExactAlarmPolicy.shouldOfferExactAlarmSettings(29))
        assertFalse(ExactAlarmPolicy.shouldOfferExactAlarmSettings(30))
        assertTrue(ExactAlarmPolicy.shouldOfferExactAlarmSettings(31))
        assertTrue(ExactAlarmPolicy.shouldOfferExactAlarmSettings(34))
    }

    @Test
    fun `OneShotGate admits exactly one acquire - the degrade note cannot re-post per arming`() {
        val gate = OneShotGate()
        assertTrue("the first degrade note must get through", gate.acquire())
        repeat(100) {
            assertFalse("later degradations must stay silent", gate.acquire())
        }
    }

    @Test
    fun `OneShotGate under concurrent arming races still yields one winner`() {
        // The armer can be called from several coroutine lanes; the AtomicBoolean
        // CAS is what keeps the note at one post per process.
        val gate = OneShotGate()
        val permits = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        val threads = (1..8).map {
            Thread { repeat(50) { permits.add(gate.acquire()) } }.also { it.start() }
        }
        threads.forEach { it.join(5_000) }
        assertEquals(1, permits.count { it })
        assertEquals(8 * 50, permits.size)
    }
}
