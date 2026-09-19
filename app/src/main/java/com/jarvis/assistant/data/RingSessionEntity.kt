package com.jarvis.assistant.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * REMEDIATION_PLAN Phase 2: durable, process-independent ring state.
 *
 * One row per alert currently ringing. `alertId` IS the alert identity
 * (it is already the AlarmManager request code and the base of the ringing
 * notification id), so it doubles as this table's primary key: at most one
 * live ring session per alert. Phase 3 owns the begin/end transitions.
 */
@Entity(tableName = "ring_sessions")
data class RingSessionEntity(
    /** [ScheduledAlertEntity.id] of the alert being rung. */
    @PrimaryKey val alertId: Int,
    /** Ring token carried by the notification actions (dismiss/snooze). */
    val token: String,
    /** ELAPSED_REALTIME timestamp the ring started (reboot-safe anchor). */
    val startedAtElapsed: Long,
    val label: String,
    val isTimer: Boolean,
    /** The trigger time that actually fired, for fire-identity guards. */
    val firedTriggerMillis: Long,
)

/** CRUD for [RingSessionEntity]; Phase 3 owns the ring lifecycle wiring. */
@Dao
interface RingSessionDao {

    /** Idempotent per alert (PK REPLACE): re-posting a ring overwrites its state. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: RingSessionEntity)

    @Query("SELECT * FROM ring_sessions WHERE alertId = :alertId LIMIT 1")
    suspend fun byAlertId(alertId: Int): RingSessionEntity?

    @Query("SELECT * FROM ring_sessions")
    suspend fun all(): List<RingSessionEntity>

    @Query("DELETE FROM ring_sessions WHERE alertId = :alertId")
    suspend fun delete(alertId: Int)

    @Query("DELETE FROM ring_sessions")
    suspend fun wipeAll()
}
