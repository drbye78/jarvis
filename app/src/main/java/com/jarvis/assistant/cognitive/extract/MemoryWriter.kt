package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.model.ValidatedFact

/**
 * COGNITIVE_PLAN §6.3: applies [NormalizationDecision]s to storage inside
 * one DAO lane. The only writer of `user_facts` — explicit memory-tool
 * writes and extraction results both land here, so the dedup/supersede/
 * contest invariants hold regardless of the entry point.
 *
 * Supersession and contest are intentionally NON-DESTRUCTIVE: the old row
 * stays as the audit trail (SUPERSEDED status), the plan's "never silently
 * overwrite" honesty rule.
 */
class MemoryWriter(
    private val factDao: UserFactDao,
    private val normalizer: FactNormalizer,
) {

    /** One applied write, for counters and tool outcomes. */
    sealed interface Applied {
        /** Re-confirmation of an existing fact. */
        data class Confirmed(val fact: FactSnapshot, val mergedConfidence: Float) : Applied

        /** Brand-new fact inserted. */
        data class Created(val fact: FactSnapshot) : Applied

        /** New fact replaced the old; both visible in the audit trail. */
        data class Superseded(val old: FactSnapshot, val new: FactSnapshot) : Applied

        /** Both claims strong: kept, flagged, the user will be asked. */
        data class Contested(val old: FactSnapshot, val new: FactSnapshot) : Applied
    }

    /**
     * Write a batch of validated facts. Each fact is classified against the
     * current ACTIVE set, and the working set is UPDATED between facts so
     * two same-key facts inside one batch compose correctly (the second sees
     * the first's result).
     */
    suspend fun writeAll(validated: List<ValidatedFact>): List<Applied> {
        if (validated.isEmpty()) return emptyList()
        val working = factDao.activeFacts().map { it.toSnapshot() }.toMutableList()
        val applied = mutableListOf<Applied>()
        for (fact in validated) {
            val decision = normalizer.classify(fact, working)
            applied.add(apply(decision))
            updateWorkingSet(working, decision)
        }
        return applied
    }

    /** Explicit memory-tool write: one fact, full confidence, EXPLICIT origin. */
    suspend fun writeExplicit(validated: ValidatedFact): Applied =
        writeAll(listOf(validated.asExplicit())).first()

    private suspend fun apply(decision: NormalizationDecision): Applied = when (decision) {
        is NormalizationDecision.ConfirmExisting -> {
            factDao.confirmFact(
                decision.existing.factId,
                decision.mergedConfidence,
                decision.now,
            )
            Applied.Confirmed(decision.existing, decision.mergedConfidence)
        }

        is NormalizationDecision.CreateNew -> {
            factDao.insert(UserFactEntity.fromSnapshot(decision.newFact))
            Applied.Created(decision.newFact)
        }

        is NormalizationDecision.Supersede -> {
            factDao.updateStatus(
                decision.oldFact.factId,
                FactStatus.SUPERSEDED.name,
                decision.newFact.updatedAt,
            )
            factDao.insert(UserFactEntity.fromSnapshot(decision.newFact))
            Applied.Superseded(decision.oldFact, decision.newFact)
        }

        is NormalizationDecision.Contest -> {
            factDao.setContested(decision.oldFact.factId, true, decision.newFact.updatedAt)
            factDao.insert(UserFactEntity.fromSnapshot(decision.newFact))
            Applied.Contested(decision.oldFact, decision.newFact)
        }
    }

    /** Mirror the applied decision into the in-memory working set. */
    private fun updateWorkingSet(
        working: MutableList<FactSnapshot>,
        decision: NormalizationDecision,
    ) {
        when (decision) {
            is NormalizationDecision.ConfirmExisting -> {
                val idx = working.indexOfFirst { it.factId == decision.existing.factId }
                if (idx >= 0) {
                    working[idx] = working[idx].copy(
                        confidence = decision.mergedConfidence,
                        lastConfirmedAt = decision.now,
                        updatedAt = decision.now,
                    )
                }
            }

            is NormalizationDecision.CreateNew -> working.add(decision.newFact)

            is NormalizationDecision.Supersede -> {
                val idx = working.indexOfFirst { it.factId == decision.oldFact.factId }
                if (idx >= 0) working[idx] = decision.oldFact.copy(status = FactStatus.SUPERSEDED)
                working.add(decision.newFact)
            }

            is NormalizationDecision.Contest -> {
                val idx = working.indexOfFirst { it.factId == decision.oldFact.factId }
                if (idx >= 0) working[idx] = decision.oldFact.copy(contested = true)
                working.add(decision.newFact)
            }
        }
    }
}
