package com.jarvis.assistant.cognitive.embed

/**
 * COGNITIVE_PLAN Phase 3 (§11: "brute-force cosine + RRF hybrid"): pure
 * Reciprocal-Rank-Fusion of the lexical lane with the vector lane.
 *
 * RRF is rank-based on purpose: the two channels produce scores on
 * incomparable scales (the ranker's 0..1 weighted mix vs cosine), and only
 * the ORDER carries dependable information. fused(i) = Σ_c 1/(k + rank_c(i))
 * with the standard k = 60 — top ranks dominate, deep ranks contribute
 * little, and a fact surfaced by BOTH channels rises above either alone.
 *
 * Determinism: ties break by first-listed-channel order (primary = the
 * lexical/ranker lane, the Phase 1 behavior), so a fixture test can assert
 * exact orderings. Pure Kotlin, no I/O.
 */
object HybridRecall {

    /** Standard RRF constant (Cormack et al. 2009). */
    const val RRF_K = 60

    /**
     * Fuse two ranked factId lists. Lists may overlap arbitrarily; ids
     * absent from both are simply absent from the result. The result keeps
     * EVERY input id (deduplicated) — callers apply the final take/spread.
     */
    fun rrfFuse(
        primary: List<String>,
        secondary: List<String>,
        k: Int = RRF_K,
    ): List<String> {
        if (primary.isEmpty()) return secondary
        if (secondary.isEmpty()) return primary
        val scores = LinkedHashMap<String, Float>()
        val firstSeen = HashMap<String, Int>()
        primary.forEachIndexed { rank, id ->
            scores[id] = (scores[id] ?: 0f) + 1f / (k + rank + 1)
            firstSeen[id] = rank
        }
        secondary.forEachIndexed { rank, id ->
            scores[id] = (scores[id] ?: 0f) + 1f / (k + rank + 1)
            if (id !in firstSeen) firstSeen[id] = primary.size + rank
        }
        return scores.keys
            .sortedWith(
                compareByDescending<String> { scores[it]!! }
                    .thenBy { firstSeen[it]!! },
            )
            .toList()
    }
}
