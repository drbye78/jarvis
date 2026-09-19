package com.jarvis.assistant

import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.session.TurnActivity
import com.jarvis.assistant.ui.StateLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for the status pill (finding N2). Pins the precedence the pure
 * [StateLabel] object promises: deaf > muted > THINKING activity > per-state.
 */
class StateLabelTest {

    @Test
    fun `deaf outranks muted and every state`() {
        assertEquals(
            R.string.state_wake_error_full,
            StateLabel.labelRes(
                AssistantState.THINKING,
                muted = true,
                deaf = true,
                activity = TurnActivity.Thinking,
            ),
        )
    }

    @Test
    fun `muted outranks a live state`() {
        assertEquals(
            R.string.state_muted,
            StateLabel.labelRes(
                AssistantState.LISTENING,
                muted = true,
                deaf = false,
                activity = null,
            ),
        )
    }

    @Test
    fun `thinking with an activity uses the activity label`() {
        assertEquals(
            R.string.activity_tool_set_alarm,
            StateLabel.labelRes(
                AssistantState.THINKING,
                muted = false,
                deaf = false,
                activity = TurnActivity.ToolRunning("setAlarm"),
            ),
        )
    }

    @Test
    fun `thinking without an activity uses the generic thinking label`() {
        assertEquals(
            R.string.state_thinking_full,
            StateLabel.labelRes(AssistantState.THINKING, muted = false, deaf = false, activity = null),
        )
    }

    @Test
    fun `activity is ignored outside thinking`() {
        assertEquals(
            R.string.state_speaking_full,
            StateLabel.labelRes(
                AssistantState.SPEAKING,
                muted = false,
                deaf = false,
                activity = TurnActivity.Thinking,
            ),
        )
    }

    @Test
    fun `a null state resolves to stopped`() {
        assertEquals(
            R.string.state_stopped,
            StateLabel.labelRes(null, muted = false, deaf = false, activity = null),
        )
    }

    @Test
    fun `every bound state maps to its full label`() {
        assertEquals(
            R.string.state_idle_full,
            StateLabel.labelRes(AssistantState.IDLE, muted = false, deaf = false, activity = null),
        )
        assertEquals(
            R.string.state_listening_full,
            StateLabel.labelRes(AssistantState.LISTENING, muted = false, deaf = false, activity = null),
        )
        assertEquals(
            R.string.state_thinking_full,
            StateLabel.labelRes(AssistantState.THINKING, muted = false, deaf = false, activity = null),
        )
        assertEquals(
            R.string.state_speaking_full,
            StateLabel.labelRes(AssistantState.SPEAKING, muted = false, deaf = false, activity = null),
        )
        assertEquals(
            R.string.state_follow_up_full,
            StateLabel.labelRes(AssistantState.FOLLOW_UP_WINDOW, muted = false, deaf = false, activity = null),
        )
    }
}
