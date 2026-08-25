package com.jarvis.assistant.session

import com.jarvis.assistant.model.AssistantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

sealed interface SessionEvent {
    data object WakeWordOrBargeIn : SessionEvent
    data object SpeechCaptured : SessionEvent
    data object NoSpeech : SessionEvent
    data class AsrFailed(val cause: Throwable? = null) : SessionEvent
    data object LlmStarted : SessionEvent
    data object LlmDone : SessionEvent
    data object PlaybackStarted : SessionEvent
    data object ErrorOccurred : SessionEvent
}

/**
 * Validated state machine. The original implementation was a total
 * event->state mapping that happily accepted impossible transitions (e.g.
 * PlaybackStarted from IDLE). Here an explicit transition table defines the
 * legal edges; unknown pairs are REJECTED (state kept + warning logged), and
 * the WakeWordOrBargeIn and ErrorOccurred events are the only global resets.
 */
object SessionTransitions {
    private val table: Map<Pair<AssistantState, SessionEvent>, AssistantState> = buildMap {
        // Normal turn
        put(AssistantState.IDLE to SessionEvent.WakeWordOrBargeIn, AssistantState.LISTENING)
        put(AssistantState.LISTENING to SessionEvent.SpeechCaptured, AssistantState.THINKING)
        put(AssistantState.LISTENING to SessionEvent.NoSpeech, AssistantState.IDLE)
        put(AssistantState.LISTENING to SessionEvent.AsrFailed(), AssistantState.IDLE)
        put(AssistantState.THINKING to SessionEvent.LlmStarted, AssistantState.THINKING)
        put(AssistantState.THINKING to SessionEvent.PlaybackStarted, AssistantState.SPEAKING)
        put(AssistantState.THINKING to SessionEvent.LlmDone, AssistantState.IDLE)
        // Streaming sentences: each new sentence keeps us in SPEAKING.
        put(AssistantState.SPEAKING to SessionEvent.PlaybackStarted, AssistantState.SPEAKING)
        put(AssistantState.SPEAKING to SessionEvent.LlmDone, AssistantState.IDLE)
        // Tool loop: after a tool result the LLM is consulted again.
        put(AssistantState.SPEAKING to SessionEvent.LlmStarted, AssistantState.THINKING)
    }

    fun next(current: AssistantState, event: SessionEvent): AssistantState? = when (event) {
        // Global events valid from any state.
        SessionEvent.WakeWordOrBargeIn -> AssistantState.LISTENING
        SessionEvent.ErrorOccurred -> AssistantState.IDLE
        else -> table[current to event]
    }
}

class SessionStateMachine {
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    fun currentState(): AssistantState = _state.value

    fun onEvent(event: SessionEvent) {
        val next = SessionTransitions.next(_state.value, event)
        if (next == null) {
            Timber.w("Rejected transition: state=%s event=%s", _state.value, event)
            return
        }
        if (next != _state.value) {
            Timber.d("State: %s --%s--> %s", _state.value, event.javaClass.simpleName, next)
        }
        _state.value = next
    }
}
