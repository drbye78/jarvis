package com.jarvis.assistant.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per schedulable alert — the unified alarm/timer store
 * (PLAN.md §3.3, items M9/S3). `id` is THE sole identity anchor:
 *  - the AlarmManager request code, used directly as an Int (no truncation,
 *    no hashing) so request codes are unique by construction and cancel is
 *    always exact (the old scheme armed timers with requestCode = base +
 *    epoch-millis truncated to Int, which wrapped mod 2³² and collided);
 *  - the base of the ringing NOTIFICATION id, banded via
 *    [com.jarvis.assistant.util.NotificationIds.ringingId] so it can never
 *    collide with the assistant notification band 0..999 (decision #3).
 *
 * Schema v1 (audit remediation decision #2): the pre-release v1→v7 chain was
 * collapsed — this is the baseline schema and pre-existing installs are wiped
 * destructively on open, so every column below (incl. [anchorTimeMillis]) is
 * created fresh by Room rather than migrated into place.
 */
@Entity(
    tableName = "scheduled_alerts",
    indices = [
        // Boot re-arm / scheduler sweeps query by (kind, enabled) ordered by
        // trigger time; `enabled` in the middle of the old index broke the
        // ordering, so it is dropped and `(kind, triggerAtMillis)` covers the
        // kind-scoped sweep (REMEDIATION_PLAN Phase 2).
        Index(
            value = ["kind", "triggerAtMillis"],
            name = "index_scheduled_alerts_kind_triggerAtMillis",
        ),
        // Standalone trigger-time index for whole-table ordering scans
        // (`all()` / `alarmsLive()` order by triggerAtMillis).
        Index(
            value = ["triggerAtMillis"],
            name = "index_scheduled_alerts_triggerAtMillis",
        ),
    ],
)
data class ScheduledAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val kind: String,
    val label: String,
    val triggerAtMillis: Long,
    /**
     * The original wall-clock anchor for recurring alarms. [com.jarvis.assistant.tools.AndroidAlarmScheduler.snooze]
     * overwrites [triggerAtMillis] with `now() + delay` but leaves this field
     * intact, so that [com.jarvis.assistant.tools.AndroidAlarmScheduler.onFired]
     * can compute the next daily occurrence from the true anchor instead of
     * drifting to the snoozed time. Defaults to [triggerAtMillis].
     */
    val anchorTimeMillis: Long = triggerAtMillis,
    val repeatDaily: Boolean = false,
    val enabled: Boolean = true,
    /**
     * Which AlarmManager clock space [triggerAtMillis] lives in
     * ([ClockDomain.RTC] for wall-clock alarms, [ClockDomain.ELAPSED] for
     * timers). Phase 3 wires arming; Phase 2 only persists it.
     */
    val clockDomain: String = ClockDomain.RTC,
    /**
     * Reboot-safe anchor for an ELAPSED-domain timer (millis since boot at
     * arm time); 0 for RTC alarms. Phase 3 owns reconciliation.
     */
    val anchorElapsedMillis: Long = 0L,
    /**
     * The elapsed anchor that was actually armed (may differ from
     * [anchorElapsedMillis] after snooze); 0 when unarmed/RTC. Phase 3 owns.
     */
    val armedElapsedMillis: Long = 0L,
) {
    companion object {
        const val KIND_ALARM = "ALARM"
        const val KIND_TIMER = "TIMER"
    }
}

/**
 * Clock domains persisted on [ScheduledAlertEntity] (REMEDIATION_PLAN
 * Phase 2). Strings (not an enum) so the column stays a plain TEXT and the
 * values are stable across process versions; the alarms lane maps them onto
 * `AlarmManager.RTC_WAKEUP` / `AlarmManager.ELAPSED_REALTIME_WAKEUP`.
 */
object ClockDomain {
    /** Wall-clock alarms (`AlarmManager.RTC_WAKEUP`). */
    const val RTC = "RTC"

    /** Boot-relative timers (`AlarmManager.ELAPSED_REALTIME_WAKEUP`). */
    const val ELAPSED = "ELAPSED"
}
