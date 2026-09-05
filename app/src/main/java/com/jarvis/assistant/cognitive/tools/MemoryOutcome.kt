package com.jarvis.assistant.cognitive.tools

import com.jarvis.assistant.tools.ToolStrings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * COGNITIVE_PLAN §6.4: the honesty contract of the memory tools — modeled
 * on [com.jarvis.assistant.tools.OpenAppOutcome]. A memory tool NEVER
 * returns a bare "ok": the outcome classifies what actually happened
 * (written / merged / needs clarification / failed / disabled) and renders
 * through [ToolStrings] so the user hears the truth in their locale.
 */
sealed interface MemoryOutcome {

    /** New fact stored. */
    data class Written(val value: String) : MemoryOutcome

    /** The fact already existed; confidence was raised instead. */
    data class Merged(val value: String) : MemoryOutcome

    /** Old and new claims both strong — the user must arbitrate. */
    data class NeedsClarification(val existing: String, val candidate: String) : MemoryOutcome

    /** Storage failure — honest failure, nothing was saved. */
    data class Failed(val detail: String?) : MemoryOutcome

    /** Master kill switch off (Settings). */
    data object Disabled : MemoryOutcome

    /** Query returned facts (value lines, already confidence-marked). */
    data class Recalled(val facts: List<String>) : MemoryOutcome

    /** Query returned nothing — say so honestly. */
    data object RecallEmpty : MemoryOutcome

    /**
     * Two-step forget, step 1: candidates listed, [confirmToken] handed to
     * the LLM to pass back with `confirmed=true` (stateless confirmation —
     * the token only exists if candidates were actually listed, plan §6.4).
     */
    data class ForgetCandidates(val candidates: List<String>, val confirmToken: String) : MemoryOutcome

    /** Two-step forget, step 2 done. */
    data class Forgotten(val value: String) : MemoryOutcome

    /** Query matched nothing forgettable. */
    data object NothingToForget : MemoryOutcome
}

/** Spoken/user-facing rendering via the ToolStrings seam (RU + EN parity). */
fun MemoryOutcome.spoken(strings: ToolStrings): String = when (this) {
    is MemoryOutcome.Written -> strings.memoryWritten(value)
    is MemoryOutcome.Merged -> strings.memoryMerged(value)
    is MemoryOutcome.NeedsClarification -> strings.memoryNeedsClarification(existing, candidate)
    is MemoryOutcome.Failed -> strings.memoryWriteFailed(detail)
    is MemoryOutcome.Disabled -> strings.memoryDisabled()
    is MemoryOutcome.Recalled -> strings.memoryRecalled(facts)
    is MemoryOutcome.RecallEmpty -> strings.memoryRecallEmpty
    is MemoryOutcome.ForgetCandidates -> strings.memoryForgetCandidates(candidates)
    is MemoryOutcome.Forgotten -> strings.memoryForgotten(value)
    is MemoryOutcome.NothingToForget -> strings.memoryNothingToForget
}

/**
 * Structured JSON for the LLM: machine-readable outcome + spoken line.
 * Built with kotlinx.serialization so escaping is the library's problem,
 * never ours (the old hand-built StringBuilder version forgot to close a
 * string quote — exactly the class of bug a serializer eliminates).
 */
fun MemoryOutcome.toJson(): String {
    val spokenValue = spoken(com.jarvis.assistant.tools.ToolStrings.Default)
    return buildJsonObject {
        when (this@toJson) {
            is MemoryOutcome.Written -> {
                put("outcome", "written")
                put("value", value)
            }
            is MemoryOutcome.Merged -> {
                put("outcome", "merged")
                put("value", value)
            }
            is MemoryOutcome.NeedsClarification -> {
                put("outcome", "needs_clarification")
                put("existing", existing)
                put("candidate", candidate)
            }
            is MemoryOutcome.Failed -> {
                put("outcome", "failed")
                put("detail", detail ?: "")
            }
            is MemoryOutcome.Disabled -> put("outcome", "disabled")
            is MemoryOutcome.Recalled -> {
                put("outcome", "recalled")
                put("facts", kotlinx.serialization.json.JsonArray(
                    facts.map { JsonPrimitive(it) },
                ))
            }
            is MemoryOutcome.RecallEmpty -> put("outcome", "empty")
            is MemoryOutcome.ForgetCandidates -> {
                put("outcome", "confirm_forget")
                put("candidates", kotlinx.serialization.json.JsonArray(
                    candidates.map { JsonPrimitive(it) },
                ))
                put("confirmToken", confirmToken)
            }
            is MemoryOutcome.Forgotten -> {
                put("outcome", "forgotten")
                put("value", value)
            }
            is MemoryOutcome.NothingToForget -> put("outcome", "not_found")
        }
        put("spoken", spokenValue)
    }.toString()
}
