package com.jarvis.assistant.util

/**
 * Process-wide notification-id allocation (audit remediation decision #3).
 *
 * Two DISJOINT bands, so an alarm notification can never overwrite (or be
 * cancelled by) a service notification:
 *
 *  - ASSISTANT band `0..999` — fixed ids owned by the service lanes:
 *    [FGS_STATE] / [FGS_PERMISSION] / [FGS_ACTIVATION] (the foreground-service
 *    ids previously hardcoded in JarvisForegroundService) and [ALARM_DEGRADE]
 *    (the one-time exact-alarm degrade note previously hardcoded as
 *    `ExactAlarmPolicy.DEGRADE_NOTIFICATION_ID = 4`). The service lane keeps
 *    its own constants until P2 switches it to consume these.
 *
 *  - ALARM band `>= [ALARM_BAND_BASE]` — one id per alert row:
 *    [ringingId] = base + row id. With AUTOINCREMENT row ids this can only
 *    ever collide with itself; it never touches the assistant band.
 *
 * **Parity by construction, unchanged:** the AlarmManager request code AND the
 * full-screen-intent PendingIntent request code stay the raw alert row id
 * ([ringingRequestCode]) — identical to [com.jarvis.assistant.tools.SystemAlertArmer]'s
 * arm/cancel codes, so FLAG_UPDATE_CURRENT updates exactly one alert's pending
 * intents. Only the NOTIFICATION id gains the band offset; the notification id
 * is not a request code and never was.
 */
object NotificationIds {
    /** Foreground-service state notification (JarvisForegroundService.NOTIFICATION_ID). */
    const val FGS_STATE = 1

    /** POST_NOTIFICATIONS permission-prompt notification. */
    const val FGS_PERMISSION = 2

    /** Boot activation prompt notification. */
    const val FGS_ACTIVATION = 3

    /** One-time exact-alarm degrade note (was ExactAlarmPolicy.DEGRADE_NOTIFICATION_ID). */
    const val ALARM_DEGRADE = 4

    /** Inclusive upper bound of the assistant band; the alarm band starts at [ALARM_BAND_BASE]. */
    const val ASSISTANT_BAND_LIMIT = 999

    /** Base of the alarm notification-id band. */
    const val ALARM_BAND_BASE = 10_000

    /**
     * Notification id for the ringing notification of alert [alertRowId].
     * Negative/unknown ids (EXTRA_ALERT_ID default = -1) collapse to the band
     * base — a legal id that still cannot collide with the assistant band.
     */
    fun ringingId(alertRowId: Int): Int = ALARM_BAND_BASE + alertRowId.coerceAtLeast(0)

    /**
     * Full-screen-intent PendingIntent request code for alert [alertRowId]:
     * the raw row id (NOT banded) — parity with the AlarmManager request code
     * in SystemAlertArmer. Negative/unknown ids collapse to 0 as before.
     */
    fun ringingRequestCode(alertRowId: Int): Int = alertRowId.coerceAtLeast(0)
}
