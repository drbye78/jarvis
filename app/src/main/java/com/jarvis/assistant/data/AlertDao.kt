package com.jarvis.assistant.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * What the caller must do with the armer after [AlertDao.applyFired] ran.
 * The scheduler arms/cancels OUTSIDE the DB transaction — this value carries
 * the exact, transaction-consistent data it must act on.
 */
sealed class FiredResolution {
    /** One-shot row was persisted as disabled — cancel any stale operation. */
    data class OneShotDisabled(val kind: String) : FiredResolution()

    /** Daily row rolled forward — arm exactly [triggerAtMillis]. */
    data class DailyRearmed(val triggerAtMillis: Long, val kind: String, val label: String) : FiredResolution()

    /** Nothing to do: row gone, already disabled, or already re-armed (the idempotency guard ran inside the transaction). */
    object NoOp : FiredResolution()
}

/**
 * What the caller must arm after [AlertDao.applyEnable]: the exact values the
 * transaction persisted, or null when nothing was armed-worthy (row gone, or
 * an expired one-shot honestly persisted as disabled — the "lying switch"
 * fix must stay honest under concurrency).
 */
data class AlertArmSpec(val id: Int, val triggerAtMillis: Long, val kind: String, val label: String)

/**
 * CRUD + ATOMIC state transitions for the unified `scheduled_alerts` store
 * (alarms + timers).
 *
 * The [Transaction] default methods below exist because the raw primitives
 * (`byId` + `update` + `setEnabled`) composed in the scheduler left
 * read-modify-write windows: a snooze racing `onFired`, or a toggle racing a
 * firing, could lose one of the writes (a full-row `update` from a stale read
 * stomps the other's change) or leave the row claiming armed while nothing
 * was armed. Each transition runs its read, its guards and its write inside
 * ONE Room transaction and returns what the caller must arm/cancel, so the
 * arming value can never come from a stale snapshot. Arming itself stays
 * OUTSIDE the transaction (PendingIntent work must not hold a DB write lock).
 *
 * On Room the defaults are wrapped in the transaction by the generated DAO;
 * JVM fakes inherit the same bodies, so the sequencing is tested off-device.
 *
 * Method names here are consumed by AndroidAlarmScheduler, the alarms UI
 * (`alarmsLive`) and the voice tools (`all`). The old `allLive()` /
 * `enabled()` queries were dead (no production call site) and are gone.
 */
@Dao
interface AlertDao {
    @Insert
    suspend fun insert(alert: ScheduledAlertEntity): Long

    @Update
    suspend fun update(alert: ScheduledAlertEntity)

    @Query("SELECT * FROM scheduled_alerts WHERE id = :id")
    suspend fun byId(id: Int): ScheduledAlertEntity?

    /** Alarms only — data source for the alarms management UI. */
    @Query("SELECT * FROM scheduled_alerts WHERE kind = 'ALARM' ORDER BY triggerAtMillis ASC")
    fun alarmsLive(): Flow<List<ScheduledAlertEntity>>

    @Query("SELECT * FROM scheduled_alerts ORDER BY triggerAtMillis ASC")
    suspend fun all(): List<ScheduledAlertEntity>

    @Query("DELETE FROM scheduled_alerts WHERE id = :id")
    suspend fun delete(id: Int)

    @Query("UPDATE scheduled_alerts SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Int, enabled: Boolean)

    /**
     * ATOMIC enable transition (UI toggle ON / boot re-arm): read the row,
     * let the caller's policy compute the trigger, persist trigger + enabled=1
     * in one transaction. When [resolveTrigger] returns null the row is
     * honestly persisted as DISABLED instead (expired one-shot — nothing
     * sensible to arm).
     *
     * @return the exact [AlertArmSpec] persisted (arm this), or null when the
     *         caller must NOT arm anything.
     */
    @Transaction
    suspend fun applyEnable(id: Int, resolveTrigger: (ScheduledAlertEntity) -> Long?): AlertArmSpec? {
        val alert = byId(id) ?: return null
        val trigger = resolveTrigger(alert)
        if (trigger == null) {
            setEnabled(id, false)
            return null
        }
        update(alert.copy(triggerAtMillis = trigger, enabled = true))
        return AlertArmSpec(id, trigger, alert.kind, alert.label)
    }

    /**
     * ATOMIC fired transition (idempotent, called from the ringing activity's
     * onCreate). Guards (row gone / already disabled / daily already re-armed)
     * run INSIDE the transaction next to the write, so two racing `onFired`
     * passes or an `onFired` racing a snooze cannot interleave a torn state.
     *
     * @param nowMillis the current time for the already-re-armed guard.
     * @param nextDailyTrigger caller-supplied roll-forward policy for a due
     *        repeat-daily row (pure function of the freshly-read entity).
     */
    @Transaction
    suspend fun applyFired(
        id: Int,
        nowMillis: Long,
        nextDailyTrigger: (ScheduledAlertEntity) -> Long,
    ): FiredResolution {
        val alert = byId(id) ?: return FiredResolution.NoOp
        if (!alert.enabled) return FiredResolution.NoOp // handled by a previous onFired
        if (!alert.repeatDaily) {
            setEnabled(id, false)
            return FiredResolution.OneShotDisabled(alert.kind)
        }
        if (alert.triggerAtMillis > nowMillis) return FiredResolution.NoOp // already re-armed
        val next = nextDailyTrigger(alert)
        update(alert.copy(triggerAtMillis = next))
        return FiredResolution.DailyRearmed(next, alert.kind, alert.label)
    }

    /**
     * ATOMIC snooze write: read the row, move the trigger into the future,
     * keep the row enabled — all in one transaction. Never touches
     * `anchorTimeMillis`, so the daily roll-forward keeps its true anchor.
     *
     * @return the [AlertArmSpec] to arm, or null when the row vanished.
     */
    @Transaction
    suspend fun applySnooze(id: Int, triggerAtMillis: Long): AlertArmSpec? {
        val alert = byId(id) ?: return null
        update(alert.copy(triggerAtMillis = triggerAtMillis, enabled = true))
        return AlertArmSpec(id, triggerAtMillis, alert.kind, alert.label)
    }
}
