package com.jarvis.assistant.cognitive.maint

import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import kotlin.math.ln
import kotlin.math.pow

/**
 * COGNITIVE_PLAN §9.1: pure maintenance math for the nightly job. Each rule
 * is a total function of its inputs (injected clock at the call site), so
 * the compaction/decay behavior is fixture-testable without a database.
 *
 * Caps (plan §4 principle 4 — bounded everything):
 * - facts ≤ [MAX_ACTIVE_FACTS] ACTIVE; overflow → lowest score → ARCHIVED
 * - confidence decay 0.99×/day for facts untouched ≥ [DECAY_AFTER_DAYS],
 *   floored at [CONFIDENCE_FLOOR]; below the floor → ARCHIVE candidate
 * - superseded chains older than [SUPERSEDED_RETENTION_DAYS] are deleted
 *   (the surviving tip keeps the history alive)
 */
object Maintenance {

    const val MAX_ACTIVE_FACTS = 500
    const val DECAY_AFTER_DAYS = 30
    const val CONFIDENCE_FLOOR = 0.2f
    const val SUPERSEDED_RETENTION_DAYS = 90

    private const val DAY_MS = 86_400_000.0
    private const val DAILY_FACTOR = 0.99

    /**
     * Decayed confidence for one fact at [nowMs]: inactive days beyond
     * [DECAY_AFTER_DAYS] multiply the stored confidence by 0.99 per day,
     * floored at [CONFIDENCE_FLOOR]. "Inactive" = neither updated nor
     * confirmed nor recalled since [updatedAt] (recall stats bump
     * lastRecalledAt WITHOUT touching updatedAt — the ranking recency is a
     * usage signal, the decay an inactivity signal; here we take the LATER
     * of the two as "last touched").
     */
    fun decayedConfidence(fact: FactSnapshot, nowMs: Long): Float {
        val lastTouched = maxOf(fact.updatedAt, fact.lastRecalledAt ?: 0L)
        val idleDays = ((nowMs - lastTouched).coerceAtLeast(0)) / DAY_MS
        val effectiveIdle = idleDays - DECAY_AFTER_DAYS
        if (effectiveIdle <= 0) return fact.confidence
        val decayed = fact.confidence * DAILY_FACTOR.pow(effectiveIdle)
        return maxOf(CONFIDENCE_FLOOR, decayed.toFloat())
    }

    /**
     * Which ACTIVE facts must move to ARCHIVED to respect the cap: the
     * lowest-scored surplus, in deletion order. Score here is the simple
     * (confidence, recency) pair the weakestActive DAO query orders by —
     * the caller passes rows in that order.
     */
    fun overCapArchiveCandidates(activeCount: Int, surplusRows: List<FactSnapshot>): List<String> {
        val surplus = activeCount - MAX_ACTIVE_FACTS
        if (surplus <= 0) return emptyList()
        return surplusRows.take(surplus).map { it.factId }
    }

    /**
     * Facts below the floor become ARCHIVE candidates (plan §9.1: "floor
     * 0.2 → ARCHIVE candidate"). Already-archived rows are never returned.
     */
    fun belowFloorArchiveCandidates(facts: List<FactSnapshot>, nowMs: Long): List<String> =
        facts.filter { it.status == FactStatus.ACTIVE }
            .filter { decayedConfidence(it, nowMs) <= CONFIDENCE_FLOOR }
            .map { it.factId }

    /**
     * Expired supersession links: SUPERSEDED rows older than the retention
     * window can be deleted entirely — the ACTIVE tip and the chain prefix
     * that is still within the window stay.
     */
    fun expiredSuperseded(facts: List<FactSnapshot>, nowMs: Long): List<String> {
        val cutoff = nowMs - (SUPERSEDED_RETENTION_DAYS * DAY_MS).toLong()
        return facts.filter { it.status == FactStatus.SUPERSEDED && it.updatedAt < cutoff }
            .map { it.factId }
    }

    /** Summary count sanity: log-friendly one-liner of a maintenance pass. */
    fun summarize(archived: Int, deleted: Int, decayed: Int): String =
        "maintenance: archived=%d deleted=%d decayed=%d".format(
            java.util.Locale.ROOT,
            archived,
            deleted,
            decayed,
        )

    /** log(1+x) helper retained for future habit/support math (plan §8.2). */
    fun log1p(x: Double): Double = ln(1.0 + x)
}
