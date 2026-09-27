package com.jarvis.assistant.tools

import com.jarvis.assistant.session.TurnOrigin

/**
 * How much damage a tool can do if the LLM invokes it on the wrong turn.
 *
 * This is a per-tool, STATIC property (declared on [ToolContract.risk]) — not
 * something the model can influence. It exists so there is an authorization
 * boundary between the LLM and tool execution: until this enum, the dispatch
 * path was a single unguarded call, and the only "control" was two prompt
 * instructions the model could ignore.
 *
 * Deliberately conservative: [IRREVERSIBLE] is the fail-closed class — a tool
 * whose effect cannot be undone from the device.
 */
enum class ToolRisk {
    /** Reads state or performs a network lookup; no local state is mutated. */
    READ_ONLY,

    /** Mutates recoverable local state (volume, playback, alarms, facts…). */
    STATEFUL,

    /** Destroys or cannot be undone (cancel alarm/timer, forget a fact). */
    IRREVERSIBLE,
}

/**
 * Provenance of the turn currently bound to the executor. Carries the session
 * layer's existing [TurnOrigin] (reused deliberately — no parallel concept)
 * plus whether the user actually issued an irreversible command this turn,
 * which is what distinguishes "the user asked for this" from "the assistant
 * decided to".
 *
 * [explicitUserCommand] is DERIVED from the turn's final ASR text by
 * [IrreversibleCommand] and rebound the moment the utterance is known — never
 * a constant. Before ASR finalizes it is false, so IRREVERSIBLE calls fail
 * CLOSED.
 */
data class TurnAuthorization(
    val origin: TurnOrigin,
    /** True only when this turn's utterance commanded a removal/cancellation. */
    val explicitUserCommand: Boolean,
) {
    companion object {
        /**
         * A user-originated voice turn. [explicitUserCommand] defaults to
         * false so a bind made before the utterance is known cannot permit an
         * IRREVERSIBLE tool; the session layer rebinds with the derived value
         * once ASR has finalized.
         */
        fun voice(explicitUserCommand: Boolean = false): TurnAuthorization =
            TurnAuthorization(origin = TurnOrigin.VOICE, explicitUserCommand = explicitUserCommand)

        /**
         * An app/system-authored off-turn action (the pause-on-wake music
         * pause), not a user command. [TurnOrigin.SCHEDULED] is the closest
         * existing provenance for "the app decided, not the user"; it can never
         * carry an explicit command, so IRREVERSIBLE stays denied.
         */
        fun system(): TurnAuthorization =
            TurnAuthorization(origin = TurnOrigin.SCHEDULED, explicitUserCommand = false)
    }
}

/**
 * Outcome of an authorization check. A sealed result, never a Boolean, so a
 * denial can carry an internal reason while the model-facing text stays
 * content-free.
 */
sealed interface AuthorizationDecision {
    data object Allow : AuthorizationDecision
    data class Deny(val reason: String) : AuthorizationDecision
}

/**
 * The pure authorization policy (NO Android imports — fully JVM-testable).
 *
 * Decision rules, verbatim:
 *  - READ_ONLY tools are always allowed.
 *  - STATEFUL tools require a bound turn context; an absent context FAILS
 *    CLOSED (the only off-turn caller — the pause-on-wake music pause — binds
 *    an explicit system-authored [TurnAuthorization.system] first).
 *  - IRREVERSIBLE tools are allowed ONLY when the bound turn is a voice turn
 *    whose utterance commanded a removal ([TurnAuthorization.explicitUserCommand]).
 *  - `forget_fact` is EXEMPT: it keeps its own independent confirmation gate
 *    (`CognitiveCoordinator.forgetFactLocked` + `ForgetConfirmation`), a
 *    two-step «list → confirm with an explicit yes» flow. Double-gating it here
 *    would deny the confirmation turn (whose utterance is «да», not a removal
 *    verb) before its own gate could run, so the new flag governs the OTHER
 *    irreversible tools only.
 */
object ToolAuthorization {

    /** The one IRREVERSIBLE tool owned by its own confirmation gate. */
    private const val FORGET_FACT = "forget_fact"

    /**
     * Sentinel turn id for app-authored off-turn calls. Real turn ids are the
     * monotonically increasing session seq (≥ 1), so a negative id can never
     * collide with — or be cleared by — a live turn's binding.
     */
    const val SYSTEM_TURN_ID: Int = -1

    fun decide(toolName: String, risk: ToolRisk, context: TurnAuthorization?): AuthorizationDecision =
        when (risk) {
            ToolRisk.READ_ONLY -> AuthorizationDecision.Allow

            ToolRisk.STATEFUL ->
                if (context == null) {
                    AuthorizationDecision.Deny("no bound turn context for a state-changing action")
                } else {
                    AuthorizationDecision.Allow
                }

            ToolRisk.IRREVERSIBLE ->
                if (permitsIrreversible(toolName, context)) {
                    AuthorizationDecision.Allow
                } else {
                    AuthorizationDecision.Deny("irreversible action without an explicit user command")
                }
        }

    /**
     * `forget_fact` is delegated to its own gate; every other IRREVERSIBLE
     * tool needs a voice turn whose [TurnAuthorization.explicitUserCommand] was
     * derived from the utterance.
     */
    private fun permitsIrreversible(toolName: String, context: TurnAuthorization?): Boolean {
        if (toolName == FORGET_FACT) return true
        return context != null && context.origin == TurnOrigin.VOICE && context.explicitUserCommand
    }
}
