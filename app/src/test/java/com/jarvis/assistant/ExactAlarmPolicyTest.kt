package com.jarvis.assistant

import com.jarvis.assistant.tools.ExactAlarmPolicy
import com.jarvis.assistant.tools.ExactAlarmPolicy.TimerSchedule
import com.jarvis.assistant.tools.OneShotGate
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * REMEDIATION_PLAN P3.1: the exact-alarm degrade-vs-exact decision matrix.
 * Pure JVM — [sdkInt] and the `canScheduleExactAlarms()` result are passed
 * in as plain values (no Robolectric; mirrors the ServicePolicy/AlarmRingerPolicy
 * extraction style, commit e07a93c).
 *
 * Android-only parts NOT covered here (by nature): the `setWindow` AlarmManager
 * call itself, the notification channel/note plumbing in SystemAlertArmer and
 * the manifest permission grant behavior.
 */
class ExactAlarmPolicyTest {

    @Test
    fun `API 29 - declared normal permission is granted, exact`() {
        assertEquals(TimerSchedule.ExactAllowWhileIdle, ExactAlarmPolicy.timerSchedule(29, null))
    }

    @Test
    fun `API 30 - declared normal permission is granted, exact`() {
        assertEquals(TimerSchedule.ExactAllowWhileIdle, ExactAlarmPolicy.timerSchedule(30, null))
    }

    @Test
    fun `API 31 with permission granted - exact`() {
        assertEquals(TimerSchedule.ExactAllowWhileIdle, ExactAlarmPolicy.timerSchedule(31, true))
        assertEquals(TimerSchedule.ExactAllowWhileIdle, ExactAlarmPolicy.timerSchedule(34, true))
    }

    @Test
    fun `API 31 with permission revoked - honest inexact degrade`() {
        assertEquals(TimerSchedule.Inexact, ExactAlarmPolicy.timerSchedule(31, false))
        assertEquals(TimerSchedule.Inexact, ExactAlarmPolicy.timerSchedule(34, false))
    }

    /** Defensive: a must-not-crash "unknown" (never true) cannot fire the exact path. */
    @Test
    fun `API 31+ with null (queried but unavailable) degrades - alarms use setAlarmClock regardless`() {
        assertEquals(TimerSchedule.Inexact, ExactAlarmPolicy.timerSchedule(31, null))
    }

    @Test
    fun `degraded window is bounded and finite`() {
        val window = ExactAlarmPolicy.INEXACT_WINDOW_MS
        org.junit.Assert.assertTrue("window must be positive, was $window", window in 1..24 * 60 * 60_000L)
        assertEquals(10 * 60 * 1000L, window)
    }

    // ---- FIX #5/#4 (P1-D): the degrade note's payload + single-fire gate ----

    @Test
    fun `the grant-screen deep-link is offered on API 31+ only`() {
        // Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM exists from 31: below
        // that the permission is a normal grant and there is nothing to open,
        // so a contentIntent there would be a dead tap.
        org.junit.Assert.assertFalse(ExactAlarmPolicy.shouldOfferExactAlarmSettings(29))
        org.junit.Assert.assertFalse(ExactAlarmPolicy.shouldOfferExactAlarmSettings(30))
        org.junit.Assert.assertTrue(ExactAlarmPolicy.shouldOfferExactAlarmSettings(31))
        org.junit.Assert.assertTrue(ExactAlarmPolicy.shouldOfferExactAlarmSettings(34))
    }

    @Test
    fun `OneShotGate admits exactly one acquire - the degrade note cannot re-post per arming`() {
        val gate = OneShotGate()
        org.junit.Assert.assertTrue("the first degrade note must get through", gate.acquire())
        repeat(100) {
            org.junit.Assert.assertFalse("later degradations must stay silent", gate.acquire())
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
