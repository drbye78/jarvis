package com.jarvis.assistant

import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.session.SessionEvent
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.session.SessionTransitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateMachineTest {

    @Test
    fun `normal turn transitions`() {
        val sm = SessionStateMachine()
        assertEquals(AssistantState.IDLE, sm.currentState())

        sm.onEvent(SessionEvent.WakeWordOrBargeIn)
        assertEquals(AssistantState.LISTENING, sm.currentState())

        sm.onEvent(SessionEvent.SpeechCaptured)
        assertEquals(AssistantState.THINKING, sm.currentState())

        sm.onEvent(SessionEvent.PlaybackStarted)
        assertEquals(AssistantState.SPEAKING, sm.currentState())

        sm.onEvent(SessionEvent.LlmDone)
        assertEquals(AssistantState.IDLE, sm.currentState())
    }

    @Test
    fun `barge-in from every state resets to LISTENING`() {
        for (state in AssistantState.values()) {
            val next = SessionTransitions.next(state, SessionEvent.WakeWordOrBargeIn)
            assertEquals("barge-in from $state", AssistantState.LISTENING, next)
        }
    }

    @Test
    fun `error from every state resets to IDLE`() {
        for (state in AssistantState.values()) {
            val next = SessionTransitions.next(state, SessionEvent.ErrorOccurred)
            assertEquals("error from $state", AssistantState.IDLE, next)
        }
    }

    @Test
    fun `illegal transitions are rejected`() {
        // The original implementation jumped IDLE -> SPEAKING on a stray
        // PlaybackStarted; the table must reject it.
        assertNull(SessionTransitions.next(AssistantState.IDLE, SessionEvent.PlaybackStarted))
        assertNull(SessionTransitions.next(AssistantState.IDLE, SessionEvent.LlmStarted))
        assertNull(SessionTransitions.next(AssistantState.LISTENING, SessionEvent.PlaybackStarted))
        assertNull(SessionTransitions.next(AssistantState.SPEAKING, SessionEvent.SpeechCaptured))
    }

    @Test
    fun `asr failure with a cause unwedges LISTENING`() {
        // Regression: the transition table keyed on the data class
        // AsrFailed() — a lookup with AsrFailed(cause) missed the entry, the
        // transition was REJECTED, and the machine stayed wedged in LISTENING
        // with a stale «Слушаю» chip after every real ASR exception.
        assertNull(
            "AsrFailed with a cause must be rejected outside LISTENING",
            SessionTransitions.next(
                AssistantState.THINKING,
                SessionEvent.AsrFailed(RuntimeException("boom")),
            ),
        )
        assertEquals(
            AssistantState.IDLE,
            SessionTransitions.next(
                AssistantState.LISTENING,
                SessionEvent.AsrFailed(java.io.IOException("gRPC deadline exceeded")),
            ),
        )
        // And the state machine itself recovers...
        val sm = SessionStateMachine()
        sm.onEvent(SessionEvent.WakeWordOrBargeIn)
        sm.onEvent(SessionEvent.AsrFailed(IllegalStateException("stream closed")))
        assertEquals(AssistantState.IDLE, sm.currentState())
    }

    @Test
    fun `consecutive sentences stay in SPEAKING`() {
        assertEquals(
            AssistantState.SPEAKING,
            SessionTransitions.next(AssistantState.SPEAKING, SessionEvent.PlaybackStarted),
        )
    }

    @Test
    fun `tool loop returns to THINKING`() {
        assertEquals(
            AssistantState.THINKING,
            SessionTransitions.next(AssistantState.SPEAKING, SessionEvent.LlmStarted),
        )
    }

    @Test
    fun `state machine ignores rejected events without crashing`() {
        val sm = SessionStateMachine()
        sm.onEvent(SessionEvent.PlaybackStarted) // illegal from IDLE
        assertEquals(AssistantState.IDLE, sm.currentState())
    }
}
