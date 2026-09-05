package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * COGNITIVE_PLAN Phase 3: the on-device embedding engine — a deterministic
 * signed-hashing bag-of-stems vectorizer over the SAME Russian-normalized
 * token stream the FTS index uses ([SearchTokenizer.tokens]).
 *
 * Why this engine (honest scope note): the plan's alternative — a
 * runtime-downloaded int8 neural model — was REJECTED by the Phase 3
 * constraints review: onnxruntime-android adds ~15–25 MB to an APK the
 * project holds at ≈160 MB (Phase 0 spent real effort removing an 11 MB
 * dead asset), a runtime download-and-verify path on a wall device is a
 * reliability and integrity failure surface, and Kirin 710A CPU inference
 * for MiniLM-class models lands in the hundreds of milliseconds — well
 * outside the §7.2 40 ms gather budget. The hashed lexical engine is
 * instant, offline, zero-egress and fully deterministic. Whether vectors
 * add recall over the lexical baseline is NOT assumed — it is measured by
 * the §10.2 retrieval gate ([EmbedderBenchmark] / RetrievalEvalTest,
 * ship-or-reject at ≥ 15 % recall@5 improvement); the cloud GigaChat
 * engine is the branch that can plausibly win it (true paraphrase /
 * synonym recall: «начальник» ↔ «руководитель»).
 *
 * Determinism: `String.hashCode()` is a JVM-spec-stable formula, so the
 * hash space is identical on CI and on the device — vectors computed in
 * tests match vectors computed at runtime for the same engine version.
 *
 * Weighting: each distinct stem contributes ±1 (signed by a second hash so
 * collisions average out instead of biasing), then L2 normalization
 * ([VectorMath]). Cosine thus measures stemmed-token-set overlap — the
 * same relevance signal as the lexical lane, expressed in a continuous
 * space the RRF fusion ([HybridRecall]) can combine with it.
 */
class LexicalEmbedder : EmbeddingEngine {

    override val engineId: String = EmbeddingEngine.LOCAL_ID
    override val kind: EmbeddingEngine.Kind = EmbeddingEngine.Kind.LOCAL
    override val dim: Int = DIM

    override suspend fun embed(texts: List<String>): List<FloatArray> =
        texts.map { embedOne(it) }

    private fun embedOne(text: String): FloatArray {
        val raw = FloatArray(DIM)
        for (stem in SearchTokenizer.tokens(text)) {
            val h = stem.hashCode()
            val slot = ((h % DIM) + DIM) % DIM
            // Signed hashing: a cheap finalize-bit decorrelates the sign
            // from the slot (h % DIM), so collisions average out instead
            // of biasing one direction.
            val sign = if (((h ushr 16) xor h) and 1 == 0) 1f else -1f
            raw[slot] += sign
        }
        return VectorMath.l2Normalize(raw)
    }

    companion object {
        /** 256 dims: 2.56 M mul-adds per 10 k facts — a few ms worst case. */
        const val DIM = 256
    }
}
