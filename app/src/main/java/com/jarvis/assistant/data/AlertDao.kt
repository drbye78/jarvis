package com.jarvis.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for the unified `scheduled_alerts` store (alarms + timers).
 * Replaces the v1 [AlarmDao]: the `alarms` table is dropped destructively in
 * schema v2. Method names here are consumed by AndroidAlarmScheduler, the
 * alarms UI (`alarmsLive`) and the voice tools (`all`).
 */
@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: ScheduledAlertEntity): Long

    @Update
    suspend fun update(alert: ScheduledAlertEntity)

    @Query("SELECT * FROM scheduled_alerts WHERE id = :id")
    suspend fun byId(id: Int): ScheduledAlertEntity?

    /** Everything: alarms + timers, enabled + disabled, by trigger time. */
    @Query("SELECT * FROM scheduled_alerts ORDER BY triggerAtMillis ASC")
    fun allLive(): Flow<List<ScheduledAlertEntity>>

    /** Alarms only — data source for the alarms management UI. */
    @Query("SELECT * FROM scheduled_alerts WHERE kind = 'ALARM' ORDER BY triggerAtMillis ASC")
    fun alarmsLive(): Flow<List<ScheduledAlertEntity>>

    @Query("SELECT * FROM scheduled_alerts ORDER BY triggerAtMillis ASC")
    suspend fun all(): List<ScheduledAlertEntity>

    @Query("SELECT * FROM scheduled_alerts WHERE enabled = 1")
    suspend fun enabled(): List<ScheduledAlertEntity>

    @Query("DELETE FROM scheduled_alerts WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("UPDATE scheduled_alerts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)
}
