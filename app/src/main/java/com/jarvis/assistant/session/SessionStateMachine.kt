package com.jarvis.assistant.session

import com.jarvis.assistant.contracts.AssistantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionEvent {
    data object WakeWordOrBargeIn : SessionEvent
    data object SpeechCaptured : SessionEvent
    data object NoSpeech : SessionEvent
    data object AsrSuccess : SessionEvent
    data class AsrFailed(val cause: Throwable? = null) : SessionEvent
    data object LlmStarted : SessionEvent
    data object LlmDone : SessionEvent
    data object PlaybackStarted : SessionEvent
    data object PlaybackComplete : SessionEvent
    data object SessionFinished : SessionEvent
    data class ErrorOccurred(val cause: Throwable? = null) : SessionEvent
}

/** Pure reducer: (current state, event) -> new state. */
fun reduceState(current: AssistantState, event: SessionEvent): AssistantState = when (event) {
    SessionEvent.WakeWordOrBargeIn -> AssistantState.LISTENING
    SessionEvent.SpeechCaptured -> AssistantState.THINKING
    SessionEvent.NoSpeech -> AssistantState.IDLE
    SessionEvent.AsrSuccess -> current
    is SessionEvent.AsrFailed -> AssistantState.IDLE
    SessionEvent.LlmStarted -> current
    SessionEvent.LlmDone -> AssistantState.IDLE
    SessionEvent.PlaybackStarted -> AssistantState.SPEAKING
    SessionEvent.PlaybackComplete -> current
    SessionEvent.SessionFinished -> AssistantState.IDLE
    is SessionEvent.ErrorOccurred -> AssistantState.IDLE
}

class SessionStateMachine {
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    fun currentState(): AssistantState = _state.value

    fun onEvent(event: SessionEvent) {
        _state.value = reduceState(_state.value, event)
    }
}
