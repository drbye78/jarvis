package com.jarvis.assistant.ui

import androidx.annotation.StringRes
import com.jarvis.assistant.R
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.session.TurnActivity
import com.jarvis.assistant.session.TurnActivityLabels

/**
 * Single source of truth for the home screen's status pill.
 *
 * The pill used to be assembled inline in `MainActivity.renderStatus()` with a
 * chain of early `return`s, so a mute or state change could leave a stale label
 * on screen and the honest `state_muted` string was dead. This object owns the
 * whole mapping; the activity only feeds it the live inputs and renders the
 * returned resource. It is pure JVM (R.string constants are compile-time ints),
 * so the truth table is testable without Robolectric.
 *
 * Precedence, highest first:
 *  1. [deaf] — the wake-word engine failed, so no state can claim to be
 *     listening; the pill shows the honest wake-failure hint.
 *  2. [muted] — the user turned the microphone off; this is a user intent that
 *     outranks any session state.
 *  3. THINKING with a non-null [activity] — the finer-grained tool label
 *     (for example "Setting an alarm…") beats the flat "Thinking…".
 *  4. Every other state — the per-state full label.
 *
 * `state == null` means no state machine is bound: the service is stopped or the
 * graph is still bootstrapping. It resolves to [R.string.state_stopped]
 * ("Assistant stopped"). That is the conservative, non-lying choice: it asserts
 * nothing about listening or thinking, unlike carrying over the last live state
 * (which is exactly how the pill used to lie when a service died mid-turn).
 */
object StateLabel {

    @StringRes
    fun labelRes(
        state: AssistantState?,
        muted: Boolean,
        deaf: Boolean,
        activity: TurnActivity?,
    ): Int = when {
        deaf -> R.string.state_wake_error_full
        muted -> R.string.state_muted
        state == AssistantState.THINKING && activity != null -> TurnActivityLabels.labelRes(activity)
        state == null -> R.string.state_stopped
        else -> stateRes(state)
    }

    @StringRes
    private fun stateRes(state: AssistantState): Int = when (state) {
        AssistantState.IDLE -> R.string.state_idle_full
        AssistantState.LISTENING -> R.string.state_listening_full
        AssistantState.THINKING -> R.string.state_thinking_full
        AssistantState.SPEAKING -> R.string.state_speaking_full
        AssistantState.FOLLOW_UP_WINDOW -> R.string.state_follow_up_full
    }
}
