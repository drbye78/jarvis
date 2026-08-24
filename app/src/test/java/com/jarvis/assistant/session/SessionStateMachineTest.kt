package com.jarvis.assistant.session

import com.jarvis.assistant.contracts.AssistantState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateMachineTest {

    @Test fun `wake word from IDLE goes to LISTENING`() {
        assertEquals(AssistantState.LISTENING, reduceState(AssistantState.IDLE, SessionEvent.WakeWordOrBargeIn))
    }

    @Test fun `barge-in from SPEAKING goes to LISTENING`() {
        assertEquals(AssistantState.LISTENING, reduceState(AssistantState.SPEAKING, SessionEvent.WakeWordOrBargeIn))
    }

    @Test fun `barge-in from THINKING goes to LISTENING`() {
        assertEquals(AssistantState.LISTENING, reduceState(AssistantState.THINKING, SessionEvent.WakeWordOrBargeIn))
    }

    @Test fun `speech captured from LISTENING goes to THINKING`() {
        assertEquals(AssistantState.THINKING, reduceState(AssistantState.LISTENING, SessionEvent.SpeechCaptured))
    }

    @Test fun `no speech from LISTENING goes to IDLE`() {
        assertEquals(AssistantState.IDLE, reduceState(AssistantState.LISTENING, SessionEvent.NoSpeech))
    }

    @Test fun `asr success preserves current state`() {
        assertEquals(AssistantState.THINKING, reduceState(AssistantState.THINKING, SessionEvent.AsrSuccess))
    }

    @Test fun `asr failed goes to IDLE`() {
        assertEquals(AssistantState.IDLE, reduceState(AssistantState.THINKING, SessionEvent.AsrFailed(RuntimeException("test"))))
    }

    @Test fun `playback started goes to SPEAKING`() {
        assertEquals(AssistantState.SPEAKING, reduceState(AssistantState.THINKING, SessionEvent.PlaybackStarted))
        assertEquals(AssistantState.SPEAKING, reduceState(AssistantState.SPEAKING, SessionEvent.PlaybackStarted))
    }

    @Test fun `llm started preserves current state`() {
        assertEquals(AssistantState.THINKING, reduceState(AssistantState.THINKING, SessionEvent.LlmStarted))
    }

    @Test fun `llm done goes to IDLE`() {
        assertEquals(AssistantState.IDLE, reduceState(AssistantState.SPEAKING, SessionEvent.LlmDone))
    }

    @Test fun `session finished goes to IDLE`() {
        assertEquals(AssistantState.IDLE, reduceState(AssistantState.LISTENING, SessionEvent.SessionFinished))
    }

    @Test fun `error occurred goes to IDLE`() {
        assertEquals(AssistantState.IDLE, reduceState(AssistantState.THINKING, SessionEvent.ErrorOccurred(RuntimeException("boom"))))
    }

    @Test fun `full happy path transitions`() {
        var state = reduceState(AssistantState.IDLE, SessionEvent.WakeWordOrBargeIn)
        assertEquals(AssistantState.LISTENING, state)
        state = reduceState(state, SessionEvent.SpeechCaptured)
        assertEquals(AssistantState.THINKING, state)
        state = reduceState(state, SessionEvent.AsrSuccess)
        assertEquals(AssistantState.THINKING, state)
        state = reduceState(state, SessionEvent.PlaybackStarted)
        assertEquals(AssistantState.SPEAKING, state)
        state = reduceState(state, SessionEvent.LlmDone)
        assertEquals(AssistantState.IDLE, state)
    }

    @Test fun `state machine onEvent updates flow`() {
        val machine = SessionStateMachine()
        assertEquals(AssistantState.IDLE, machine.currentState())
        machine.onEvent(SessionEvent.WakeWordOrBargeIn)
        assertEquals(AssistantState.LISTENING, machine.currentState())
    }
}
