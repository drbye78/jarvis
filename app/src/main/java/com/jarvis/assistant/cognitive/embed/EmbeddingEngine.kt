package com.jarvis.assistant.cognitive.embed

/**
 * COGNITIVE_PLAN Phase 3: the EmbeddingEngine seam (plan §11: "EmbeddingEngine
 * seam + entitlement check of the GigaChat embeddings endpoint").
 *
 * One engine is ACTIVE at a time (the user-visible `memory.embedder`
 * selector, §12.4-3). Every stored vector is stamped with [engineId]; a
 * cosine is only ever computed inside ONE engine's space — switching the
 * selector invalidates stored vectors and the backfill rebuilds them.
 *
 * Contract:
 * - returned vectors MUST be L2-normalized ([VectorMath.l2Normalize]) — the
 *   hot path computes cosine as a plain dot product;
 * - [embed] is suspend because the CLOUD engine performs network calls; the
 *   LOCAL engine returns immediately and must never block;
 * - failures throw (LlmHttpException/IOException for cloud) — callers own
 *   the degrade-quiet policy (gather falls back to lexical-only, the tool
 *   path reports honestly).
 */
interface EmbeddingEngine {
    val engineId: String
    val kind: EmbeddingEngine.Kind
    val dim: Int

    /** One vector per input text, same order, L2-normalized. */
    suspend fun embed(texts: List<String>): List<FloatArray>

    /**
     * §12.4-3 entitlement probe. Default: the engine is always usable
     * (LOCAL); the cloud engine overrides with the real endpoint probe.
     */
    suspend fun checkEntitlement(): Entitlement = Entitlement.Ok

    enum class Kind { LOCAL, CLOUD }

    /** Entitlement probe verdicts (§12.4-3). */
    sealed interface Entitlement {

        /** Endpoint works — cloud branch usable. */
        object Ok : Entitlement

        /** 4xx: the account/model has no entitlement. */
        data class Denied(val code: Int) : Entitlement

        /** 429/5xx/transport: no verdict — retry later. */
        data class Transient(val code: Int) : Entitlement
    }

    /** Engine registry (the §12.4-3 selector values map onto these). */
    companion object {
        /** On-device hashed lexical engine — always available, zero egress. */
        const val LOCAL_ID = "local-lexical-v1"

        /** GigaChat embeddings endpoint (entitlement-gated). */
        const val CLOUD_ID = "gigachat-embeddings"
    }
}

/**
 * F5 (REMEDIATION_PLAN): the recorded cloud-entitlement verdict.
 *
 * The benchmark writes two stamps into `memory_meta` — `cloudEmbedEntitled`
 * (epoch ms of the last OK probe) and `cloudEmbedUnavailable` (HTTP code of the
 * last Denied probe). They are MUTUALLY EXCLUSIVE: a verdict REPLACES the other,
 * never accumulates beside it. Before this, `resolveActiveEngine` ignored both
 * keys and treated "a cloud engine object exists" as usable, so:
 * - an account that had been DENIED still routed facts to the cloud (the
 *   `CLOUD` selector and an AUTO cloud winner both stayed live), and
 * - a probe that failed after a success left the stale success in place.
 *
 * Read side ([isUsable]) and write side ([nextStamps]) are kept in one pure
 * place so the two can never disagree.
 */
object CloudEntitlement {

    /** What a probe verdict does to the two stamps (null stamp = clear it). */
    data class Stamps(val entitledAt: Long?, val unavailableCode: Int?)

    /**
     * The stamps a verdict leaves behind, or null for "no verdict recorded".
     * Ok → entitled stamp, unavailability cleared. Denied → unavailability
     * stamp, entitled stamp CLEARED (the revocation case). Transient → null:
     * a network hiccup must not overwrite a real verdict with a blank slate.
     */
    fun nextStamps(probe: EmbeddingEngine.Entitlement, now: Long): Stamps? = when (probe) {
        is EmbeddingEngine.Entitlement.Ok -> Stamps(entitledAt = now, unavailableCode = null)
        is EmbeddingEngine.Entitlement.Denied -> Stamps(entitledAt = null, unavailableCode = probe.code)
        is EmbeddingEngine.Entitlement.Transient -> null
    }

    /**
     * True only when the engine exists AND the last recorded verdict was a
     * success AND no unavailability stamp contradicts it.
     */
    fun isUsable(
        engineConstructed: Boolean,
        entitledStamp: String?,
        unavailableStamp: String?,
    ): Boolean = engineConstructed && entitledStamp != null && unavailableStamp == null
}

/** Selector values behind the `memory.embedder` pref (§12.4-3). */
enum class EmbedderChoice {
    /** Benchmark winner (memory_meta); CI ship verdict as fallback; else OFF. */
    AUTO,

    /** GigaChat embeddings (facts/queries egress — disclosed in Settings). */
    CLOUD,

    /** On-device lexical engine (no egress, fits the 40 ms gather budget). */
    LOCAL,

    /** No vectors at all — byte-identical to the Phase 2 read path. */
    OFF;

    companion object {
        fun fromPref(raw: String?): EmbedderChoice =
            entries.firstOrNull { it.name == raw } ?: AUTO
    }
}

/**
 * Pure selection logic for the `memory.embedder` selector (§12.4-3: "the
 * outcome becomes the default of a user-visible selector"). Fail-closed:
 * every unavailable branch resolves to OFF (no vectors), never to a
 * guessed engine.
 *
 * - AUTO → the on-device benchmark winner when usable; without one the CI
 *   ship-or-reject verdict for the LOCAL branch decides (§10.2) — a REJECT
 *   keeps vectors OFF until an on-device benchmark (possibly with cloud
 *   entitlement) proves a winner;
 * - CLOUD → only with a constructed cloud engine AND a passed entitlement
 *   probe;
 * - LOCAL → always resolvable (the lexical engine has no external deps).
 */
object EmbedderSelection {

    fun resolve(
        choice: EmbedderChoice,
        benchmarkWinner: String?,
        cloudUsable: Boolean,
        localShipsByCiGate: Boolean,
    ): String? = when (choice) {
        EmbedderChoice.OFF -> null
        EmbedderChoice.LOCAL -> EmbeddingEngine.LOCAL_ID
        EmbedderChoice.CLOUD -> EmbeddingEngine.CLOUD_ID.takeIf { cloudUsable }
        EmbedderChoice.AUTO -> when {
            benchmarkWinner == EmbeddingEngine.CLOUD_ID && cloudUsable -> EmbeddingEngine.CLOUD_ID
            benchmarkWinner == EmbeddingEngine.LOCAL_ID -> EmbeddingEngine.LOCAL_ID
            localShipsByCiGate -> EmbeddingEngine.LOCAL_ID
            else -> null
        }
    }
}
