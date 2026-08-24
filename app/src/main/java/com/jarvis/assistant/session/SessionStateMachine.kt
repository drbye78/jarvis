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
    data object AsrFailed : SessionEvent
    data object LlmStarted : SessionEvent
    data object LlmDone : SessionEvent
    data object PlaybackStarted : SessionEvent
    data object PlaybackComplete : SessionEvent
    data object SessionFinished : SessionEvent
    data object ErrorOccurred : SessionEvent
}

sealed interface SessionAction {
    data object None : SessionAction
    data object StartListening : SessionAction
    data object StartThinking : SessionAction
    data object StartSpeaking : SessionAction
    data object ReturnToIdle : SessionAction
    data object NotifyError : SessionAction
}

class SessionStateMachine {
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    fun currentState(): AssistantState = _state.value

    fun onEvent(event: SessionEvent): SessionAction {
        return when (event) {
            SessionEvent.WakeWordOrBargeIn -> { _state.value = AssistantState.LISTENING; SessionAction.StartListening }
            SessionEvent.SpeechCaptured -> { _state.value = AssistantState.THINKING; SessionAction.StartThinking }
            SessionEvent.NoSpeech -> { _state.value = AssistantState.IDLE; SessionAction.ReturnToIdle }
            SessionEvent.AsrSuccess -> SessionAction.None
            SessionEvent.AsrFailed -> { _state.value = AssistantState.IDLE; SessionAction.NotifyError }
            SessionEvent.LlmStarted -> SessionAction.None
            SessionEvent.LlmDone -> { _state.value = AssistantState.IDLE; SessionAction.ReturnToIdle }
            SessionEvent.PlaybackStarted -> { _state.value = AssistantState.SPEAKING; SessionAction.StartSpeaking }
            SessionEvent.PlaybackComplete -> SessionAction.None
            SessionEvent.SessionFinished -> { _state.value = AssistantState.IDLE; SessionAction.ReturnToIdle }
            SessionEvent.ErrorOccurred -> { _state.value = AssistantState.IDLE; SessionAction.NotifyError }
        }
    }
}