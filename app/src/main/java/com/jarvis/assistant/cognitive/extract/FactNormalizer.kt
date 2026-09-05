package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.ValidatedFact
import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * COGNITIVE_PLAN §6.3: normalization of validated fact candidates against
 * the existing fact set — dedup, supersession, contest. Pure Kotlin with an
 * injected clock and id generator, fixture-tested.
 *
 * Decision matrix (plan §6.3, with the one underspecified corner documented):
 *
 * 1. SAME identity — exact `(subject, predicate, valueNormalized)` match, OR
 *    same `(subject, predicate)` with paraphrase-level lexical overlap
 *    ≥ [PARAPHRASE_OVERLAP] on the normalized values ("люблю фильмы
 *    Тарковского" vs "обожаю Тарковского") → CONFIRM the existing fact:
 *    confidence is raised toward the accumulated evidence (weighted average
 *    leaning 2:1 to the stored value, capped at [CONFIDENCE_CAP], and never
 *    allowed to DECREASE — a reconfirmation can only add support),
 *    `lastConfirmedAt` bumps.
 * 2. SAME `(subject, predicate)`, different value → supersession candidate.
 *    - new ≥ old − 0.1 confidence AND both strong (≥ [STRONG_CONFIDENCE]) →
 *      CONTEST: both facts stay ACTIVE, both marked contested=true; the
 *      prompt composer renders the pair with an instruction to ask the user
 *      (honesty over silent overwrite).
 *    - new ≥ old − 0.1 (weaker old, or new weak) → SUPERSEDE: old becomes
 *      SUPERSEDED, the new fact carries `supersedesId` → old.
 *    - new < old − 0.1 (a much weaker conflicting claim) → CREATE_NEW: the
 *      plan leaves this corner open; we keep both ACTIVE and let ranking +
 *      the user resolve it (a weak contradictory extraction must not destroy
 *      a strong stored fact, and marking every weak conflict "contested"
 *      would pollute the prompt with disputes).
 *
 * Forget/correction never destroys history: SUPERSEDED/FORGOTTEN rows stay
 * in the table as the audit trail (plan principle 1).
 */
class FactNormalizer(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { com.jarvis.assistant.cognitive.model.Ids.uuidV7() },
) {

    /**
     * Decide what writing [incoming] against [existing] (the current ACTIVE
     * set for this subject/predicate scope) must do. Returns one decision;
     * the caller maps it to DAO operations inside a transaction.
     */
    fun classify(
        incoming: ValidatedFact,
        existing: List<FactSnapshot>,
    ): NormalizationDecision {
        val now = nowMs()
        val valueNorm = SearchTokenizer.normalize(incoming.value)
        val subject = SearchTokenizer.normalize(incoming.subject).ifBlank { "user" }

        // Rule 1 — same identity (exact or paraphrase).
        val sameKey = existing.filter {
            it.subject == subject && it.predicate == incoming.predicate
        }
        val duplicate = sameKey.firstOrNull {
            it.valueNormalized == valueNorm ||
                SearchTokenizer.overlap(it.valueNormalized, valueNorm) >= PARAPHRASE_OVERLAP
        }
        if (duplicate != null) {
            val merged = mergeConfidence(duplicate.confidence, incoming.confidence)
            return NormalizationDecision.ConfirmExisting(
                existing = duplicate,
                mergedConfidence = merged,
                now = now,
            )
        }

        // Rule 2 — same (subject, predicate), different value.
        val conflicting = sameKey
            .filter { it.status == com.jarvis.assistant.cognitive.model.FactStatus.ACTIVE }
            .maxByOrNull { it.updatedAt } // the freshest stored claim wins the comparison
        if (conflicting != null) {
            val bothStrong =
                incoming.confidence >= STRONG_CONFIDENCE &&
                    conflicting.confidence >= STRONG_CONFIDENCE
            val canSupersede = incoming.confidence >= conflicting.confidence - CONFIDENCE_TOLERANCE
            val candidate = snapshot(
                incoming = incoming,
                factId = newId(),
                subject = subject,
                valueNorm = valueNorm,
                now = now,
                supersedesId = conflicting.factId,
            )
            return when {
                // Both claims strong: an honest tie — keep both, ask the user
                // (plan §6.3: honesty over silent overwrite).
                bothStrong ->
                    NormalizationDecision.Contest(conflicting, candidate.copy(contested = true))

                // The new claim is at least close to the stored one → replace,
                // keeping the old row as the audit trail.
                canSupersede -> NormalizationDecision.Supersede(conflicting, candidate)

                // A much weaker contradictory claim: plan leaves this open —
                // keep both ACTIVE and let ranking + the user resolve it. A
                // weak extraction must not destroy (or dispute-shadow) a
                // strong stored fact.
                else -> NormalizationDecision.CreateNew(candidate)
            }
        }

        // Rule 3 — brand new fact.
        return NormalizationDecision.CreateNew(
            snapshot(
                incoming = incoming,
                factId = newId(),
                subject = subject,
                valueNorm = valueNorm,
                now = now,
                supersedesId = null,
            ),
        )
    }

    /**
     * Weighted confidence merge for a reconfirmed fact: 2:1 toward the
     * accumulated value, monotonic (never decreases), capped.
     */
    internal fun mergeConfidence(old: Float, incoming: Float): Float =
        minOf(CONFIDENCE_CAP, maxOf(old, (2f * old + incoming) / 3f))

    private fun snapshot(
        incoming: ValidatedFact,
        factId: String,
        subject: String,
        valueNorm: String,
        now: Long,
        supersedesId: String?,
    ): FactSnapshot = FactSnapshot(
        factId = factId,
        category = incoming.category,
        subject = subject,
        predicate = incoming.predicate,
        value = incoming.value.trim(),
        valueNormalized = valueNorm,
        confidence = incoming.confidence.coerceIn(0f, 1f),
        origin = incoming.origin,
        status = com.jarvis.assistant.cognitive.model.FactStatus.ACTIVE,
        supersedesId = supersedesId,
        contested = false,
        sensitive = incoming.sensitive,
        // 0 = explicit memory-tool write with no source message (plan §6.4).
        sourceMessageId = incoming.messageId.takeIf { it > 0 },
        createdAt = now,
        updatedAt = now,
        lastConfirmedAt = now,
        lastRecalledAt = null,
        recallCount = 0,
    )

    companion object {
        /** Above this, a conflict is a dispute to ask about, not a correction. */
        const val STRONG_CONFIDENCE = 0.75f

        /** Supersession tolerance: new must reach old − 0.1 (plan §6.3). */
        const val CONFIDENCE_TOLERANCE = 0.1f

        /** Lexical overlap at which two values count as the same fact. */
        const val PARAPHRASE_OVERLAP = 0.8f

        private const val CONFIDENCE_CAP = 0.99f
    }
}

/** One normalization outcome (plan §6.3). Sealed = exhaustive call sites. */
sealed interface NormalizationDecision {

    /** Re-statement of a stored fact: raise confidence, bump confirm time. */
    data class ConfirmExisting(
        val existing: FactSnapshot,
        val mergedConfidence: Float,
        val now: Long,
    ) : NormalizationDecision

    /** New value replaces the old; the old stays as the audit trail. */
    data class Supersede(
        val oldFact: FactSnapshot,
        val newFact: FactSnapshot,
    ) : NormalizationDecision

    /** Both claims strong and comparable: keep both, flag the dispute. */
    data class Contest(
        val oldFact: FactSnapshot,
        val newFact: FactSnapshot,
    ) : NormalizationDecision

    /** Nothing similar stored: insert. */
    data class CreateNew(
        val newFact: FactSnapshot,
    ) : NormalizationDecision
}

/** Convenience: explicit memory-tool writes carry full confidence. */
fun ValidatedFact.asExplicit(): ValidatedFact = copy(
    origin = FactOrigin.EXPLICIT,
    confidence = 1f,
)
