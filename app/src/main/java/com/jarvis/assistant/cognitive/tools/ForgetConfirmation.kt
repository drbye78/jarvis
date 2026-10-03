package com.jarvis.assistant.cognitive.tools

import com.jarvis.assistant.tools.AffirmativeUtterance

/**
 * Cognitive-facing name for the explicit-affirmative matcher used by the
 * forget confirmation gate.
 *
 * The vocabulary and matching rules live in
 * [AffirmativeUtterance] and are SHARED with the MCP write-confirmation gate —
 * this object is a thin delegating alias so every existing cognitive call site
 * and test keeps its name. Do not re-declare the token sets here.
 */
object ForgetConfirmation {

    /**
     * True only for an explicit affirmative. Null/blank, a question, a
     * negation, a long statement, or any out-of-vocabulary token returns
     * false — the conservative default is refusal.
     */
    fun isAffirmative(utterance: String?): Boolean = AffirmativeUtterance.isAffirmative(utterance)
}
