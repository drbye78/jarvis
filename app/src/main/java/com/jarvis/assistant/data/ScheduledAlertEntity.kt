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
        // trigger time; the composite index keeps that scan index-covered.
        Index(
            value = ["kind", "enabled", "triggerAtMillis"],
            name = "index_scheduled_alerts_kind_enabled_triggerAtMillis",
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
) {
    companion object {
        const val KIND_ALARM = "ALARM"
        const val KIND_TIMER = "TIMER"
    }
}
