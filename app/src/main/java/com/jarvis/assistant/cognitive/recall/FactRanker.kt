package com.jarvis.assistant.cognitive.recall

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactSnapshot
import kotlin.math.ln
import kotlin.math.pow

/**
 * COGNITIVE_PLAN §7.2: the pure ranking function for prompt injection.
 *
 * ```
 * score = 0.35 * confidence
 *       + 0.25 * recencyDecay(updatedAt, now)        // half-life 60 days
 *       + 0.15 * usageTerm(log(1 + recallCount))
 *       + 0.15 * categoryWeight(cat)                 // IDENTITY/RELATION > PREFERENCE > OTHER
 *       + 0.10 * lexicalOverlap(utteranceTokens, factTokens)
 * ```
 *
 * A lexical FTS hit adds a flat [LEXICAL_HIT_BOOST] on top (plan §7.2:
 * "lexical hit ⇒ score boost 0.3") — applied by the caller that knows the
 * FTS results, not by this class.
 *
 * Deterministic for a given (facts, utterance, now): ties break by
 * (score desc, category, factId) — the ordering the snapshot tests rely on.
 * Pure Kotlin, injected clock, no Android imports.
 */
class FactRanker(private val nowMs: () -> Long = System::currentTimeMillis) {

    /**
     * Top-k selection with a per-category spread (plan §7.2: max
     * [maxPerCategory] from one category) so a hundred music preferences
     * cannot crowd out the user's name.
     */
    fun topFacts(
        facts: List<FactSnapshot>,
        utterance: String?,
        limit: Int = DEFAULT_LIMIT,
        maxPerCategory: Int = MAX_PER_CATEGORY,
    ): List<ScoredFact> {
        val utteranceTokens = utterance?.let { SearchTokenizer.tokens(it).toSet() }.orEmpty()
        val ranked: List<ScoredFact> = facts.asSequence()
            .filter { it.status == com.jarvis.assistant.cognitive.model.FactStatus.ACTIVE }
            .map { scored(it, utteranceTokens) }
            .sortedWith(
                compareByDescending<ScoredFact> { it.score }
                    .thenBy { it.fact.category.name }
                    .thenBy { it.fact.factId },
            )
            .toList()
        val picked = mutableListOf<ScoredFact>()
        for (candidate in ranked) {
            val perCategory = picked.count { it.fact.category == candidate.fact.category }
            if (perCategory < maxPerCategory) picked.add(candidate)
            if (picked.size >= limit) break
        }
        return picked
    }

    /** The plan's score for one fact against the (possibly empty) utterance. */
    fun score(fact: FactSnapshot, utteranceTokens: Set<String>): Float {
        val recency = recencyDecay(fact.updatedAt, nowMs())
        val usage = usageTerm(fact.recallCount)
        val overlap = if (utteranceTokens.isEmpty()) {
            0f
        } else {
            val factTokens = SearchTokenizer.tokens(fact.value + " " + fact.subject).toSet()
            if (factTokens.isEmpty()) {
                0f
            } else {
                utteranceTokens.intersect(factTokens).size.toFloat() /
                    utteranceTokens.size.coerceAtLeast(1)
            }
        }
        return W_CONFIDENCE * fact.confidence.coerceIn(0f, 1f) +
            W_RECENCY * recency +
            W_USAGE * usage +
            W_CATEGORY * categoryWeight(fact.category) +
            W_OVERLAP * overlap
    }

    private fun scored(fact: FactSnapshot, utteranceTokens: Set<String>) =
        ScoredFact(fact, score(fact, utteranceTokens), lexicalHit = false)

    companion object {
        const val DEFAULT_LIMIT = 5
        const val MAX_PER_CATEGORY = 2

        /** Flat boost added to facts that matched the FTS query (plan §7.2). */
        const val LEXICAL_HIT_BOOST = 0.3f

        /** Recency half-life in days (plan §7.2: 60). */
        private const val RECENCY_HALF_LIFE_DAYS = 60.0
        private const val DAY_MS = 86_400_000.0

        /** recallCount at which usageTerm saturates to ~1. */
        private const val USAGE_NORM = 10.0

        private const val W_CONFIDENCE = 0.35f
        private const val W_RECENCY = 0.25f
        private const val W_USAGE = 0.15f
        private const val W_CATEGORY = 0.15f
        private const val W_OVERLAP = 0.10f

        /** 0.5^(ageDays / 60) — plan §7.2. */
        fun recencyDecay(updatedAt: Long, nowMs: Long): Float {
            val ageDays = ((nowMs - updatedAt).coerceAtLeast(0)) / DAY_MS
            return 0.5.pow(ageDays / RECENCY_HALF_LIFE_DAYS).toFloat()
        }

        /** log(1 + recallCount) normalized to [0, 1] at USAGE_NORM recalls. */
        fun usageTerm(recallCount: Int): Float =
            (ln(1.0 + recallCount) / ln(1.0 + USAGE_NORM)).toFloat().coerceIn(0f, 1f)

        /** IDENTITY/RELATION anchor the user; PREFERENCE flavors them. */
        fun categoryWeight(category: FactCategory): Float = when (category) {
            FactCategory.IDENTITY, FactCategory.RELATION -> 1.0f
            FactCategory.HEALTH -> 0.8f
            FactCategory.PREFERENCE, FactCategory.POSSESSION, FactCategory.GOAL,
            FactCategory.ROUTINE,
            -> 0.7f
            FactCategory.OTHER -> 0.4f
        }
    }
}

/** One ranked fact with its score and lexical-match provenance. */
data class ScoredFact(
    val fact: FactSnapshot,
    val score: Float,
    val lexicalHit: Boolean,
)
