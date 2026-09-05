package com.jarvis.assistant.cognitive.embed

import kotlin.math.sqrt

/**
 * COGNITIVE_PLAN Phase 3 (§11): pure vector math for the semantic-recall
 * lane. Everything here is deterministic, allocation-frugal and free of
 * Android imports so it is fixture-testable on the JVM — the same
 * discipline as SearchTokenizer/FactRanker.
 *
 * Storage format: vectors are persisted as little-endian float32 BLOBs
 * (Room `ByteArray`). The codec below is the single derivation point in
 * both directions — a byte-order bug here would silently corrupt cosine
 * similarity, so `floatsToBytes ∘ bytesToFloats` is round-trip tested.
 *
 * Cosine note: every engine MUST store L2-normalized vectors
 * ([l2Normalize] is part of the engine contract, see [EmbeddingEngine]).
 * For normalized vectors cosine(a, b) == dot(a, b), so the hot loop is a
 * plain dot product — no sqrt, no division. At the plan's §12.3 bound
 * (< 10 000 facts × 256 dims) a full brute-force scan is a few
 * milliseconds — sqlite-vec stays a non-goal (§12.3).
 */
object VectorMath {

    /** Cosine top-K over pre-normalized candidate vectors (id → vector). */
    fun topK(
        query: FloatArray,
        candidates: List<Pair<String, FloatArray>>,
        k: Int,
    ): List<String> {
        if (k <= 0 || candidates.isEmpty()) return emptyList()
        return candidates
            .map { (id, vec) -> id to dot(query, vec) }
            .sortedWith(compareByDescending<Pair<String, Float>> { it.second }.thenBy { it.first })
            .take(k)
            .map { it.first }
    }

    /** Dot product; short vectors are safe (missing dims contribute 0). */
    fun dot(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var acc = 0f
        for (i in 0 until n) acc += a[i] * b[i]
        return acc
    }

    /** L2 normalization (returns a new array; zero vector → zero vector). */
    fun l2Normalize(v: FloatArray): FloatArray {
        var sq = 0f
        for (x in v) sq += x * x
        if (sq <= 0f) return FloatArray(v.size)
        val inv = 1f / sqrt(sq)
        return FloatArray(v.size) { v[it] * inv }
    }

    /** float32 → little-endian bytes (storage codec, see class KDoc). */
    fun floatsToBytes(v: FloatArray): ByteArray {
        val out = ByteArray(v.size * 4)
        for (i in v.indices) {
            val bits = v[i].toRawBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    /** Little-endian bytes → float32 (storage codec, see class KDoc). */
    fun bytesToFloats(b: ByteArray): FloatArray {
        require(b.size % 4 == 0) { "vector blob size ${b.size} is not a multiple of 4" }
        val out = FloatArray(b.size / 4)
        for (i in out.indices) {
            val bits = (b[i * 4].toInt() and 0xFF) or
                ((b[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((b[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((b[i * 4 + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(bits)
        }
        return out
    }
}
