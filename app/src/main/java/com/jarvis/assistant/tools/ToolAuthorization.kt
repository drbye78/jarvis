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
enum class ToolRisk(val requiresConfirmation: Boolean = false) {
    /** Reads state or performs a network lookup; no local state is mutated. */
    READ_ONLY,

    /** Mutates recoverable local state (volume, playback, alarms, facts…). */
    STATEFUL,

    /** Destroys or cannot be undone (cancel alarm/timer, forget a fact). */
    IRREVERSIBLE,

    /**
     * An UNTRUSTED, dynamically-discovered external (MCP) tool. Unlike the
     * built-in classes, its behavior is NOT declared by our code — it is
     * whatever a third-party server exposes over the network, and a READ
     * server can still be a prompt-injection carrier. It is therefore the
     * STRICTEST class: allowed only on a bound, user-originated VOICE turn
     * (see [ToolAuthorization.decide]). A proactive/scheduled turn or an
     * off-turn call is denied, because the user did not ask for the external
     * side effect in that moment.
     */
    EXTERNAL,

    /**
     * A WRITE-access external (MCP) tool — the strongest class. It carries
     * every risk of [EXTERNAL] PLUS a side effect on a third-party system, so
     * the EXACT call (server + tool + canonical arguments) must additionally
     * be confirmed by an explicit affirmative on the immediately-next voice
     * turn (see [WriteConfirmation]). This authorization layer only enforces
     * the VOICE-turn requirement; the confirmation clause is layered on top by
     * the registry. [requiresConfirmation] is true for this class only.
     */
    EXTERNAL_WRITE(requiresConfirmation = true),

    /**
     * Voice-turn-only, user-originated LOCAL control (e.g. the R13 management
     * surface, and the smart-home control R4 will add). Like [EXTERNAL] it is
     * allowed only on a bound, user-originated VOICE turn; a
     * proactive/scheduled turn or an off-turn call is denied, because the user
     * did not ask for the local side effect in that moment. It is deliberately
     * NOT a blanket relaxation: a tool in this class that needs the two-turn
     * affirmative additionally implements [ConfirmedTool], and the registry
     * layers that clause on top — [requiresConfirmation] itself stays false so
     * the confirmation requirement is per-tool, never per-class.
     */
    CONTROLLED,
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
 *  - EXTERNAL (dynamically-discovered MCP) tools are allowed ONLY when a turn
 *    context is bound AND its [TurnOrigin] is VOICE. PROACTIVE/SCHEDULED turns
 *    and an absent context all DENY: an unvetted third-party tool may only run
 *    when the user's own voice initiated the turn.
 *  - EXTERNAL_WRITE (MCP tools with a side effect) follows the SAME rule here
 *    (bound VOICE turn only); the registry additionally requires an
 *    affirmative confirmation of the exact call before executing it.
 *  - CONTROLLED (voice-turn-only user-originated local control) follows the
 *    SAME rule as EXTERNAL/EXTERNAL_WRITE (bound VOICE turn only). A
 *    CONTROLLED tool that needs the two-turn affirmative implements
 *    [ConfirmedTool]; the registry layers that clause on top.
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

            ToolRisk.EXTERNAL ->
                if (permitsVoiceTurn(context)) {
                    AuthorizationDecision.Allow
                } else {
                    // Content-free: never name the server/tool or echo a payload.
                    AuthorizationDecision.Deny("external tool requires a bound voice turn")
                }

            ToolRisk.EXTERNAL_WRITE ->
                if (permitsVoiceTurn(context)) {
                    AuthorizationDecision.Allow
                } else {
                    // Content-free: never name the server/tool or echo a payload.
                    AuthorizationDecision.Deny("external write requires a bound voice turn")
                }

            ToolRisk.CONTROLLED ->
                if (permitsVoiceTurn(context)) {
                    AuthorizationDecision.Allow
                } else {
                    // Content-free: never name the action or echo a payload.
                    AuthorizationDecision.Deny("controlled local action requires a bound voice turn")
                }
        }

    /**
     * The voice-only classes ([ToolRisk.EXTERNAL], [ToolRisk.EXTERNAL_WRITE]
     * and [ToolRisk.CONTROLLED]) are the least trusted: a bound turn is not
     * enough, its origin must be the user's VOICE. PROACTIVE/SCHEDULED turns
     * are the assistant acting on its own, which must never reach a
     * third-party server or flip a local control surface.
     */
    private fun permitsVoiceTurn(context: TurnAuthorization?): Boolean =
        context != null && context.origin == TurnOrigin.VOICE

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
