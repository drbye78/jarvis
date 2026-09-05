package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.recall.FactRanker
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import kotlinx.serialization.Serializable

/**
 * COGNITIVE_PLAN §10.2 + Phase 3: the retrieval-quality gate.
 *
 * "50 query→expected-fact pairs; lexical baseline vs hybrid; ship vectors
 * only at ≥ 15 % recall@5 improvement; negative result documented
 * otherwise." The harness is pure and CI-runnable: the lexical baseline
 * reproduces the production read path exactly (FactRanker + the FTS
 * prefix-match emulation over the same [SearchTokenizer.indexText] stream
 * the real `fact_fts` column stores), the hybrid fuses that baseline with
 * an engine's cosine ranking through [HybridRecall.rrfFuse].
 *
 * The fixtures are RU retrieval probes covering the cases where a plain
 * token overlap is expected to struggle: paraphrase (bag→рюкзак), synonym
 * predicates («кто мой начальник» → «работаю у Иванова»), morphology the
 * light suffix strip does not fold, and multi-hop value mentions.
 */
object EmbedderBenchmark {

    const val IMPROVEMENT_THRESHOLD = 0.15
    const val RECALL_K = 5
    const val VECTOR_CHANNEL_K = 8

    @Serializable
    data class FixtureFact(
        val factId: String,
        val category: String = "OTHER",
        val subject: String = "user",
        val predicate: String = "other",
        val value: String,
    )

    @Serializable
    data class Fixture(
        val id: String,
        val facts: List<FixtureFact>,
        val query: String,
        val expectedFactIds: List<String>,
    )

    @Serializable
    data class EngineReport(
        val engineId: String,
        val baselineRecallAt5: Double,
        val hybridRecallAt5: Double,
        val improvement: Double,
    ) {
        /** §10.2 ship-or-reject. */
        fun ships(): Boolean =
            hybridRecallAt5 >= baselineRecallAt5 * (1.0 + IMPROVEMENT_THRESHOLD)

        override fun toString(): String =
            java.lang.String.format(
                java.util.Locale.ROOT,
                "engine=%s baseline@5=%.3f hybrid@5=%.3f improvement=%.1f%% ship=%s",
                engineId,
                baselineRecallAt5,
                hybridRecallAt5,
                improvement * 100.0,
                ships(),
            )
    }

    /** A candidate engine for the hybrid lane (fakes allowed in tests). */
    fun interface EngineAdapter {
        suspend fun embed(texts: List<String>): List<FloatArray>
    }

    /** Mean recall@5 over fixtures (fixtures without expectations are skipped). */
    suspend fun evaluate(
        fixtures: List<Fixture>,
        engine: EngineAdapter?,
    ): EngineReport {
        var baselineSum = 0.0
        var hybridSum = 0.0
        var counted = 0
        for (fixture in fixtures) {
            if (fixture.expectedFactIds.isEmpty()) continue
            counted++
            val baseline = lexicalRanking(fixture)
            baselineSum += recallAtK(baseline, fixture.expectedFactIds)
            if (engine != null) {
                val hybrid = hybridRanking(fixture, engine)
                hybridSum += recallAtK(hybrid, fixture.expectedFactIds)
            }
        }
        require(counted > 0) { "no fixtures with expectations" }
        val baselineRecall = baselineSum / counted
        val hybridRecall = if (engine == null) baselineRecall else hybridSum / counted
        val improvement = if (baselineRecall <= 0.0) {
            0.0
        } else {
            (hybridRecall - baselineRecall) / baselineRecall
        }
        return EngineReport(
            engineId = engine?.let { "candidate" } ?: "lexical-baseline-only",
            baselineRecallAt5 = baselineRecall,
            hybridRecallAt5 = hybridRecall,
            improvement = improvement,
        )
    }

    /** Production-faithful lexical lane (ranker + FTS-prefix hits). */
    fun lexicalRanking(fixture: Fixture): List<String> {
        val snapshots = fixture.facts.map { it.toSnapshot() }
        val ranker = FactRanker(nowMs = { 0L })
        val ranked = ranker.topFacts(
            snapshots,
            fixture.query,
            limit = snapshots.size.coerceAtLeast(1),
            maxPerCategory = snapshots.size.coerceAtLeast(1),
        )
        val ftsHits = ftsHitIds(fixture)
        return ranked
            .map { scored ->
                if (scored.fact.factId in ftsHits) {
                    scored.copy(score = scored.score + FactRanker.LEXICAL_HIT_BOOST)
                } else {
                    scored
                }
            }
            // Re-sort deterministically (score desc, then factId) — same
            // ordering the coordinator applies after the FTS boost.
            .sortedWith(
                compareByDescending<com.jarvis.assistant.cognitive.recall.ScoredFact> { it.score }
                    .thenBy { it.fact.factId },
            )
            .map { it.fact.factId }
    }

    /** Baseline + RRF-fused cosine channel from [engine]. */
    suspend fun hybridRanking(fixture: Fixture, engine: EngineAdapter): List<String> {
        val baseline = lexicalRanking(fixture)
        if (baseline.isEmpty()) return baseline
        val texts = fixture.facts.map {
            SearchTokenizer.indexText(it.subject, it.value, it.category)
        } + fixture.query
        val vectors = engine.embed(texts)
        val queryVec = vectors.last()
        val factVectors = vectors.dropLast(1)
        val vectorRanking = VectorMath.topK(
            queryVec,
            fixture.facts.mapIndexed { i, f -> f.factId to factVectors[i] },
            k = VECTOR_CHANNEL_K,
        )
        return HybridRecall.rrfFuse(baseline, vectorRanking)
    }

    /**
     * Emulates `UserFactDao.searchActive(matchQuery)`: FTS4 MATCH over the
     * pre-tokenized `searchText` column with OR-joined prefix terms — a
     * fact is a hit when ANY of its index tokens starts with ANY query
     * term. This is exactly the semantics of
     * [SearchTokenizer.matchQuery] against [SearchTokenizer.indexText].
     */
    private fun ftsHitIds(fixture: Fixture): Set<String> {
        val queryTerms = SearchTokenizer.matchQuery(fixture.query)?.split(" OR ")
            ?.map { it.removeSuffix("*") }
            ?.toSet()
            ?: return emptySet()
        return fixture.facts
            .filter { fact ->
                val tokens = SearchTokenizer.tokens(
                    fact.subject + " " + fact.value + " " + fact.category,
                )
                tokens.any { token -> queryTerms.any { term -> token.startsWith(term) } }
            }
            .mapTo(HashSet()) { it.factId }
    }

    private fun recallAtK(ranking: List<String>, expected: List<String>): Double {
        if (expected.isEmpty()) return 0.0
        val top = ranking.take(RECALL_K).toSet()
        return expected.count { it in top }.toDouble() / expected.size
    }

    private fun FixtureFact.toSnapshot(): FactSnapshot = FactSnapshot(
        factId = factId,
        category = FactCategory.valueOf(category),
        subject = subject,
        predicate = predicate,
        value = value,
        valueNormalized = SearchTokenizer.normalize(value),
        confidence = 0.9f,
        origin = FactOrigin.EXPLICIT,
        status = FactStatus.ACTIVE,
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        createdAt = 0L,
        updatedAt = 0L,
        lastConfirmedAt = 0L,
        lastRecalledAt = null,
        recallCount = 0,
    )
}
