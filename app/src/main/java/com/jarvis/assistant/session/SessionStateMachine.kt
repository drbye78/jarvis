package com.jarvis.assistant.session

import com.jarvis.assistant.model.AssistantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** Explicit cancellation of everything (cancelAll) — global reset to IDLE (M6). */
    data object Cancelled : SessionEvent

    /** Follow-up window opened after a spoken turn drained (no barge-in). */
    data object FollowUpWindowOpened : SessionEvent

    /** VAD onset inside the follow-up window — a wake-word-free turn begins. */
    data object FollowUpSpeechDetected : SessionEvent

    /** Follow-up window elapsed with no speech. */
    data object FollowUpWindowExpired : SessionEvent
}

/**
 * Validated state machine. The original implementation was a total
 * event->state mapping that happily accepted impossible transitions (e.g.
 * PlaybackStarted from IDLE). Here an explicit transition table defines the
 * legal edges; unknown pairs are REJECTED (state kept + warning logged), and
 * the WakeWordOrBargeIn, ErrorOccurred and Cancelled events are the only
 * global resets.
 */
object SessionTransitions {
    private val table: Map<Pair<AssistantState, SessionEvent>, AssistantState> = buildMap {
        // Normal turn
        put(AssistantState.IDLE to SessionEvent.WakeWordOrBargeIn, AssistantState.LISTENING)
        put(AssistantState.LISTENING to SessionEvent.SpeechCaptured, AssistantState.THINKING)
        put(AssistantState.LISTENING to SessionEvent.NoSpeech, AssistantState.IDLE)
        put(AssistantState.THINKING to SessionEvent.LlmStarted, AssistantState.THINKING)
        put(AssistantState.THINKING to SessionEvent.PlaybackStarted, AssistantState.SPEAKING)
        put(AssistantState.THINKING to SessionEvent.LlmDone, AssistantState.IDLE)
        // Streaming sentences: each new sentence keeps us in SPEAKING.
        put(AssistantState.SPEAKING to SessionEvent.PlaybackStarted, AssistantState.SPEAKING)
        put(AssistantState.SPEAKING to SessionEvent.LlmDone, AssistantState.IDLE)
        // Tool loop: after a tool result the LLM is consulted again.
        put(AssistantState.SPEAKING to SessionEvent.LlmStarted, AssistantState.THINKING)
        // Follow-up window: opened from IDLE (the turn's LlmDone fired
        // first), VAD onset starts the next turn, silence expires to IDLE.
        put(AssistantState.IDLE to SessionEvent.FollowUpWindowOpened, AssistantState.FOLLOW_UP_WINDOW)
        put(AssistantState.FOLLOW_UP_WINDOW to SessionEvent.FollowUpSpeechDetected, AssistantState.LISTENING)
        put(AssistantState.FOLLOW_UP_WINDOW to SessionEvent.FollowUpWindowExpired, AssistantState.IDLE)
        // Safety: a straggler LlmDone while the window is open must not wedge.
        put(AssistantState.FOLLOW_UP_WINDOW to SessionEvent.LlmDone, AssistantState.FOLLOW_UP_WINDOW)
    }

    fun next(current: AssistantState, event: SessionEvent): AssistantState? = when (event) {
        // Global events valid from any state.
        SessionEvent.WakeWordOrBargeIn -> AssistantState.LISTENING
        SessionEvent.ErrorOccurred -> AssistantState.IDLE
        SessionEvent.Cancelled -> AssistantState.IDLE
        // AsrFailed carries a Throwable, so a data-class TABLE key like
        // `AsrFailed()` would only ever match a cause-less failure — every
        // real ASR exception missed the lookup, the transition was REJECTED,
        // and the machine wedged in LISTENING with a stale «Слушаю» chip.
        // Match by TYPE, from LISTENING, regardless of the cause payload.
        is SessionEvent.AsrFailed ->
            if (current == AssistantState.LISTENING) AssistantState.IDLE else null
        else -> table[current to event]
    }
}

class SessionStateMachine {
    private val _state = MutableStateFlow(AssistantState.IDLE)
    val state: StateFlow<AssistantState> = _state.asStateFlow()
    private val mutex = Mutex()

    fun currentState(): AssistantState = _state.value

    suspend fun onEvent(event: SessionEvent) = mutex.withLock {
        val next = SessionTransitions.next(_state.value, event)
        if (next == null) {
            Timber.w("Rejected transition: state=%s event=%s", _state.value, event)
            return@withLock
        }
        if (next != _state.value) {
            Timber.d("State: %s --%s--> %s", _state.value, event.javaClass.simpleName, next)
        }
        _state.value = next
    }
}
