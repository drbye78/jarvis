package com.jarvis.assistant

import com.jarvis.assistant.tools.ExactAlarmPolicy
import com.jarvis.assistant.tools.ExactAlarmPolicy.TimerSchedule
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
}
