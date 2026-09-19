package com.jarvis.assistant.ui

import com.jarvis.assistant.model.AssistantState

/**
 * Visual state of the voice orb. Top-level (and therefore JVM-testable)
 * rather than nested in [VoiceOrbView]: the state -> shape decision is pure
 * logic and should not need Robolectric to verify.
 */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, FOLLOW_UP, MUTED, DEAF }

/**
 * Pure state -> shape mapping for the orb (U12).
 *
 * The orb already changes colour per state, but colour alone must never be
 * the only channel: a colour-blind user, or anyone in bright sunlight, needs
 * a second signal. Each state therefore owns a distinct SHAPE as well —
 * MUTED is a ring with a diagonal slash, DEAF is a ring with a radial tick,
 * FOLLOW_UP always carries an arc segment. This object decides which shape;
 * [VoiceOrbView] only draws it.
 */
object OrbShape {

    /**
     * Minimum sweep of the FOLLOW_UP countdown arc, in degrees.
     *
     * The arc is drawn from the remaining window fraction, so without a floor
     * it would shrink to nothing as the window expires and FOLLOW_UP would
     * become indistinguishable from LISTENING by shape. The floor keeps the
     * shape signal alive; the true remaining time is still what the sweep
     * magnitude approximates, and a truly expired window leaves FOLLOW_UP
     * entirely.
     */
    const val MIN_FOLLOW_UP_ARC_DEGREES = 12f

    /**
     * Resolve the orb shape. [deaf] wins over [muted] (a failed wake-word
     * engine is not a user choice), and both win over the session state: the
     * orb must never look like it is listening when it cannot hear or has
     * been muted.
     */
    fun of(state: AssistantState?, muted: Boolean, deaf: Boolean = false): OrbState = when {
        deaf -> OrbState.DEAF
        muted -> OrbState.MUTED
        state == null -> OrbState.IDLE
        else -> stateShape(state)
    }

    private fun stateShape(state: AssistantState): OrbState = when (state) {
        AssistantState.LISTENING -> OrbState.LISTENING
        AssistantState.THINKING -> OrbState.THINKING
        AssistantState.SPEAKING -> OrbState.SPEAKING
        AssistantState.FOLLOW_UP_WINDOW -> OrbState.FOLLOW_UP
        AssistantState.IDLE -> OrbState.IDLE
    }
}
