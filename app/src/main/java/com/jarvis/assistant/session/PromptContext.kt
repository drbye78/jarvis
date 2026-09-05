package com.jarvis.assistant.session

/**
 * COGNITIVE_PLAN §7.1: everything one turn contributes to the system prompt.
 * Built ONCE per turn by [TurnRunner] (right after ASR finalizes) and reused
 * by every LLM pass inside the turn — per-pass FRESH time is preserved by
 * the composer re-rendering the clock, while the memory gather result is
 * shared (it is a point-in-time DB read; re-gathering per pass would only
 * burn the latency budget, the plan's TurnState lesson applied to context).
 *
 * [memory] is a lazy provider so the gather can be STARTED as soon as the
 * utterance is final (before the LLM request is even built — plan §7.2:
 * "launched the moment ASR finalizes") and awaited only when the composer
 * needs it, hidden inside GigaChat's time-to-first-token. The provider
 * returns the ALREADY RENDERED `<memory-context>` block («» empty string
 * when memory is off/empty) so the session layer carries no cognitive types
 * and the composer stays a dumb assembler (byte-identity is then trivially
 * assertable).
 */
data class PromptContext(
    /** The finalized user utterance; null for non-utterance passes. */
    val utterance: String?,

    /** Hour of day (0–23) at SpeechCaptured, device timezone. */
    val hour: Int,

    /** Day of week (Calendar.DAY_OF_WEEK) at SpeechCaptured. */
    val dayOfWeek: Int,

    /** True when this session was opened from the follow-up window. */
    val isFollowUp: Boolean,

    /**
     * Suspended-once provider of the rendered memory block; defaults to an
     * always-empty section for tests and the pre-cognitive baseline. MUST be
     * idempotent within the turn.
     */
    val memory: suspend () -> String = { "" },
) {
    companion object {
        /** Baseline context for tests and non-turn callers. */
        fun blank(utterance: String? = null): PromptContext = PromptContext(
            utterance = utterance,
            hour = 12,
            dayOfWeek = java.util.Calendar.MONDAY,
            isFollowUp = false,
        )
    }
}

/**
 * Origin of one conversational turn (COGNITIVE_PLAN §3/§8.1). PROACTIVE
 * turns are never ingested into memory — the assistant must not learn from
 * its own voice — and are tagged in telemetry from Phase 2.
 */
enum class TurnOrigin { VOICE, PROACTIVE, SCHEDULED }

/**
 * The cognitive turn seam as far as the session layer is concerned
 * (COGNITIVE_PLAN §6.1/§7.2): the per-turn memory read (already rendered,
 * budget-enforced) and the fire-and-forget write after the user message is
 * persisted. Implemented by the CognitiveCoordinator; null = pre-cognitive
 * behaviour for tests/baseline.
 */
interface CognitiveTurnHooks {
    /**
     * Rendered `<memory-context>` block for the turn ("" when memory is
     * off/empty/degraded). Implementations must self-bound the cost
     * (coordinator: `withTimeout(40 ms)`) and never throw except
     * cancellation. The utterance drives the lexical union (plan §7.2).
     */
    suspend fun gather(utterance: String?): String

    /** Fire-and-forget ingest of a persisted user message (plan §6.1). */
    fun ingest(utterance: String, messageId: Long, origin: TurnOrigin)
}
