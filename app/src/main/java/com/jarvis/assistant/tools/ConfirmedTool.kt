package com.jarvis.assistant.tools

/**
 * Implemented by a tool whose exact invocation must be confirmed by an
 * explicit affirmative on the immediately-next voice turn, regardless of
 * WHICH domain the action belongs to.
 *
 * This is the DOMAIN-NEUTRAL half of the confirmation seam. The registry
 * derives the single-use challenge key from `(confirmationDomain,
 * confirmationAction, canonical arguments)` via [WriteBinding] — a digest,
 * never raw arguments. Two implementers exist:
 *
 *  - an external (MCP) WRITE tool ([ToolRisk.EXTERNAL_WRITE]), through
 *    [ConfirmedWriteTool], where the domain is the server id and the action
 *    is the server's original tool name; and
 *  - a local [ToolRisk.CONTROLLED] action (e.g. opening the R13 management
 *    surface on the LAN), where the domain/action are local stable tokens.
 *
 * The security invariants are owned by [WriteConfirmation] and [WriteBinding]
 * and are IDENTICAL for both: the confirmation is derived ONLY from the user's
 * ASR text plus local state, there is deliberately no token/nonce the model can
 * echo, the challenge is single-use, it expires after the TTL, and it is bound
 * to one exact call (bait-and-switch guard). Do not add any model-authorable
 * authorization here.
 */
interface ConfirmedTool {
    /**
     * Stable identity of the confirmation DOMAIN — the MCP configured server
     * id, or a local domain token (e.g. `"management"`). Never model-authored.
     */
    val confirmationDomain: String

    /**
     * The action within [confirmationDomain] — the server's original,
     * un-namespaced tool name, or a local action token (e.g. `"lan"`). Never
     * model-authored.
     */
    val confirmationAction: String
}
