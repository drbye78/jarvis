package com.jarvis.assistant.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per schedulable alert — the unified alarm/timer store
 * (PLAN.md §3.3, items M9/S3). `id` is THE sole AlarmManager request-code
 * authority: an Int used directly, with no truncation and no hashing, so
 * request codes are unique by construction and cancel is always exact.
 * (The old scheme armed timers with requestCode = base + epoch-millis
 * truncated to Int, which wrapped mod 2³² and collided.)
 *
 * Schema v2 replaces the v1 `alarms` table destructively: upgrading over a
 * v1 install wipes old alarm rows AND chat history (fallbackToDestructive-
 * Migration drops every table). Accepted by the owner — no backward
 * compatibility is kept.
 */
@Entity(tableName = "scheduled_alerts")
data class ScheduledAlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val kind: String,
    val label: String,
    val triggerAtMillis: Long,
    val repeatDaily: Boolean = false,
    val enabled: Boolean = true,
) {
    companion object {
        const val KIND_ALARM = "ALARM"
        const val KIND_TIMER = "TIMER"
    }
}
