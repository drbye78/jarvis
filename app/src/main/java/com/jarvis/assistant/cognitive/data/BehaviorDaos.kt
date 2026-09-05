package com.jarvis.assistant.cognitive.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * COGNITIVE_PLAN §5/§8: behaviour-layer DAOs (telemetry, habits, decisions,
 * summaries). Plain interfaces — JVM-testable via fakes, exactly like the
 * memory-core DAOs (CognitiveDaos.kt).
 */
@Dao
interface CommandEventDao {

    @Insert
    suspend fun insert(row: CommandEventEntity): Long

    /** Total rows — the recorder's "every 10th event" recompute trigger. */
    @Query("SELECT COUNT(*) FROM command_events")
    suspend fun countAll(): Int

    /**
     * Raw VOICE/ok events since [since] for the habit-eligible tools — the
     * clustering itself (hour buckets, support counts) is pure Kotlin in
     * HabitDetector so it stays deterministic and JVM-testable (SQLite
     * localtime is not).
     */
    @Query(
        "SELECT * FROM command_events WHERE at >= :since AND ok = 1 " +
            "AND origin = 'VOICE' AND tool IN (:tools)",
    )
    suspend fun voiceOkSince(since: Long, tools: List<String>): List<CommandEventEntity>

    /** Retention (§5): events older than 90 days are deleted; rules persist. */
    @Query("DELETE FROM command_events WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    /** «Забыть всё» (plan §9.2): telemetry is cognitive data too — wiped. */
    @Query("DELETE FROM command_events")
    suspend fun wipeAll()
}

@Dao
interface HabitRuleDao {

    @Insert
    suspend fun insert(rule: HabitRuleEntity): Long

    @Update
    suspend fun update(rule: HabitRuleEntity)

    @Query("SELECT * FROM habit_rules WHERE tool = :tool AND argsFingerprint = :fingerprint " +
        "AND hourBucket IS :hourBucket AND kind = :kind LIMIT 1")
    suspend fun byKey(
        tool: String,
        fingerprint: String,
        hourBucket: Int?,
        kind: String,
    ): HabitRuleEntity?

    @Query("SELECT * FROM habit_rules ORDER BY id ASC")
    suspend fun all(): List<HabitRuleEntity>

    /** The arbiter evaluates only rules that can still speak. */
    @Query("SELECT * FROM habit_rules WHERE state IN ('PROBATION', 'ACTIVE') ORDER BY id ASC")
    suspend fun candidateRules(): List<HabitRuleEntity>

    @Query("SELECT * FROM habit_rules WHERE tool = :tool AND argsFingerprint = :fingerprint")
    suspend fun byFingerprint(tool: String, fingerprint: String): List<HabitRuleEntity>

    @Query("SELECT * FROM habit_rules WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): HabitRuleEntity?

    @Query("DELETE FROM habit_rules")
    suspend fun wipeAll()
}

@Dao
interface BehaviorLogDao {

    @Insert
    suspend fun insert(row: BehaviorLogEntity): Long

    /** §8.3 gate 6: global daily quota — FIRED rows since start of day. */
    @Query("SELECT COUNT(*) FROM behavior_log WHERE decision = 'FIRED' AND at >= :since")
    suspend fun firedSince(since: Long): Int

    @Query("SELECT * FROM behavior_log WHERE ruleId = :ruleId ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun latestForRule(ruleId: Long): BehaviorLogEntity?

    /** §8.2 reject detection: was a suggestion FIRED within [since]? */
    @Query("SELECT * FROM behavior_log WHERE decision = 'FIRED' AND at >= :since " +
        "ORDER BY at DESC, id DESC LIMIT 1")
    suspend fun latestFiredSince(since: Long): BehaviorLogEntity?

    /** DEFERRED throttle: ≥1 row for the rule since [since]? */
    @Query("SELECT COUNT(*) FROM behavior_log WHERE ruleId = :ruleId AND at >= :since")
    suspend fun countForRuleSince(ruleId: Long, since: Long): Int

    /** Retention (§5): 30 days. */
    @Query("DELETE FROM behavior_log WHERE at < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM behavior_log")
    suspend fun wipeAll()
}

@Dao
interface SessionSummaryDao {

    @Insert
    suspend fun insert(row: SessionSummaryEntity): Long

    @Query("SELECT * FROM session_summaries WHERE kind = 'DAILY' ORDER BY toAt DESC, id DESC LIMIT 1")
    suspend fun latestDaily(): SessionSummaryEntity?

    /** SESSION rows the DAILY digest does not cover yet (§7.1 SummarySection). */
    @Query("SELECT * FROM session_summaries WHERE kind = 'SESSION' AND toAt > :fromAt " +
        "ORDER BY toAt ASC, id ASC")
    suspend fun sessionsAfter(fromAt: Long): List<SessionSummaryEntity>

    /** DAILY digest input: SESSION rows created since this time. */
    @Query("SELECT * FROM session_summaries WHERE kind = 'SESSION' AND toAt >= :fromAt " +
        "ORDER BY toAt ASC, id ASC")
    suspend fun sessionsSince(fromAt: Long): List<SessionSummaryEntity>

    /** The SESSION cursor: summaries of messages up to this id already exist. */
    @Query("SELECT * FROM session_summaries WHERE kind = 'SESSION' ORDER BY toMessageId DESC, id DESC LIMIT 1")
    suspend fun latestSession(): SessionSummaryEntity?

    @Query("SELECT COUNT(*) FROM session_summaries WHERE kind = 'DAILY'")
    suspend fun countDaily(): Int

    @Query("SELECT * FROM session_summaries WHERE kind = 'DAILY' ORDER BY toAt ASC, id ASC LIMIT 1")
    suspend fun oldestDaily(): SessionSummaryEntity?

    @Query("DELETE FROM session_summaries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM session_summaries")
    suspend fun wipeAll()
}

/**
 * Inert composite for tests and pre-behaviour call sites (the coordinator's
 * constructor defaults): every method is a no-op or returns an empty result,
 * so the behaviour layer silently does nothing without touching a database.
 */
// 25 trivial one-liners: four DAO interfaces composited into one inert
// object. Splitting it would add ceremony without reducing risk.
@Suppress("TooManyFunctions")
object NoopBehaviorDaos : CommandEventDao, HabitRuleDao, BehaviorLogDao, SessionSummaryDao {
    override suspend fun insert(row: CommandEventEntity): Long = 0
    override suspend fun countAll(): Int = 0
    override suspend fun voiceOkSince(since: Long, tools: List<String>): List<CommandEventEntity> = emptyList()
    override suspend fun deleteOlderThan(cutoff: Long): Int = 0

    override suspend fun insert(rule: HabitRuleEntity): Long = 0
    override suspend fun update(rule: HabitRuleEntity) = Unit
    override suspend fun byKey(tool: String, fingerprint: String, hourBucket: Int?, kind: String): HabitRuleEntity? = null
    override suspend fun byFingerprint(tool: String, fingerprint: String): List<HabitRuleEntity> = emptyList()
    override suspend fun all(): List<HabitRuleEntity> = emptyList()
    override suspend fun candidateRules(): List<HabitRuleEntity> = emptyList()
    override suspend fun byId(id: Long): HabitRuleEntity? = null

    override suspend fun insert(row: BehaviorLogEntity): Long = 0
    override suspend fun firedSince(since: Long): Int = 0
    override suspend fun latestForRule(ruleId: Long): BehaviorLogEntity? = null
    override suspend fun latestFiredSince(since: Long): BehaviorLogEntity? = null
    override suspend fun countForRuleSince(ruleId: Long, since: Long): Int = 0
    // deleteOlderThan: one override satisfies both CommandEventDao and
    // BehaviorLogDao (identical signatures).

    override suspend fun insert(row: SessionSummaryEntity): Long = 0
    override suspend fun latestDaily(): SessionSummaryEntity? = null
    override suspend fun sessionsAfter(fromAt: Long): List<SessionSummaryEntity> = emptyList()
    override suspend fun sessionsSince(fromAt: Long): List<SessionSummaryEntity> = emptyList()
    override suspend fun latestSession(): SessionSummaryEntity? = null
    override suspend fun countDaily(): Int = 0
    override suspend fun oldestDaily(): SessionSummaryEntity? = null
    override suspend fun deleteById(id: Long) = Unit

    /** One override satisfies the four identical interface declarations. */
    override suspend fun wipeAll() = Unit
}
