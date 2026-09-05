package com.jarvis.assistant.cognitive.model

/**
 * COGNITIVE_PLAN Phase 1: domain vocabulary of the memory core.
 *
 * Everything here is pure Kotlin (no Android imports) so the normalizer,
 * ranker, parser and eval harness are fixture-testable on the JVM — the same
 * discipline as [com.jarvis.assistant.session.SessionStateMachine].
 *
 * Storage note: the Room entity adds its own autoincrement rowid (Room's
 * external-content FTS4 requires an INTEGER primary key to drive the content
 * sync triggers). [FactSnapshot.factId] is the stable, time-ordered UUIDv7
 * identity the plan specifies — supersession chains, inspector rows and
 * exports all reference `factId`, never the internal rowid.
 */

/** Where a fact came from (plan §5: provenance is not optional). */
enum class FactOrigin {
    /** The user said it explicitly («запомни, что…», «меня зовут…»). */
    EXPLICIT,

    /** The extraction LLM derived it from an utterance, evidence-anchored. */
    INFERRED,

    /** Derived from other facts/system state (Phase 2+; reserved). */
    DERIVED,
}

/** Lifecycle of a fact (plan §5). Never destructive-by-default. */
enum class FactStatus {
    /** In the prompt budget, recallable. */
    ACTIVE,

    /** Replaced by a newer fact (chain via [FactSnapshot.supersedesId]). */
    SUPERSEDED,

    /** User requested forgetting (two-step confirm). Excluded everywhere. */
    FORGOTTEN,

    /** Over-cap compaction: kept for history, excluded from prompts. */
    ARCHIVED,

    /** Failed validation (e.g. evidence mismatch) — parked, never silently dropped. */
    QUARANTINED,
}

/** Coarse topic class of a fact (plan §5; drives ranking weight + sensitivity). */
enum class FactCategory {
    IDENTITY,
    RELATION,
    PREFERENCE,
    ROUTINE,
    POSSESSION,
    GOAL,
    HEALTH,
    OTHER,
}

/**
 * Pure, immutable view of one stored fact — the currency of the normalizer
 * and the ranker. Built from [com.jarvis.assistant.cognitive.data.UserFactEntity].
 */
data class FactSnapshot(
    val factId: String,
    val category: FactCategory,
    val subject: String,
    val predicate: String,
    val value: String,
    val valueNormalized: String,
    val confidence: Float,
    val origin: FactOrigin,
    val status: FactStatus,
    val supersedesId: String?,
    val contested: Boolean,
    val sensitive: Boolean,
    val sourceMessageId: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val lastConfirmedAt: Long,
    val lastRecalledAt: Long?,
    val recallCount: Int,
) {
    /** Identity key for dedup: same (subject, predicate, normalized value). */
    val key: FactKey get() = FactKey(subject, predicate, valueNormalized)
}

/** Normalized identity of a fact (plan §6.3). */
data class FactKey(
    val subject: String,
    val predicate: String,
    val valueNormalized: String,
)

/**
 * One fact candidate as produced by the extraction parser AFTER strict local
 * validation (confidence clamp, evidence-anchored anti-hallucination,
 * predicate whitelist — plan §6.2). This is the input to the normalizer;
 * nothing unvalidated ever reaches storage.
 */
data class ValidatedFact(
    val subject: String,
    val predicate: String,
    val value: String,
    val confidence: Float,
    val evidence: String,
    val messageId: Long,
    val category: FactCategory,
    val sensitive: Boolean,
    val origin: FactOrigin = FactOrigin.INFERRED,
)

/**
 * Time-ordered UUIDv7 generator (plan §5: `user_facts` external identity).
 * Layout per RFC 9562: 48-bit Unix millisecond timestamp, version 7,
 * RFC-4122 variant, 74 random bits. Time-ordering keeps facts sortable by
 * creation without a secondary index and makes exports stable.
 */
object Ids {

    private val random = java.security.SecureRandom()

    fun uuidV7(nowMs: Long = System.currentTimeMillis()): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        // 48-bit big-endian timestamp over the top bytes.
        bytes[0] = ((nowMs ushr 40) and 0xFF).toByte()
        bytes[1] = ((nowMs ushr 32) and 0xFF).toByte()
        bytes[2] = ((nowMs ushr 24) and 0xFF).toByte()
        bytes[3] = ((nowMs ushr 16) and 0xFF).toByte()
        bytes[4] = ((nowMs ushr 8) and 0xFF).toByte()
        bytes[5] = (nowMs and 0xFF).toByte()
        // version 7 (high nibble of byte 6), variant 10xx (top bits of byte 8).
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x70).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
        return formatHex(bytes)
    }

    private fun formatHex(bytes: ByteArray): String {
        val sb = StringBuilder(36)
        bytes.forEachIndexed { i, b ->
            if (i in intArrayOf(4, 6, 8, 10)) sb.append('-')
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"
}
