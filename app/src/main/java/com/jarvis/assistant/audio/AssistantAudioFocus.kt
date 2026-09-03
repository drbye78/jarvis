package com.jarvis.assistant.audio

/**
 * M6 fix: the assistant's own TTS now requests audio focus with
 * AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK before speaking, so compliant players
 * (Яндекс Музыка included) duck their stream for the spoken confirmation
 * instead of talking over it — the standard assistant behavior. Before this,
 * the app never touched AudioManager at all.
 *
 * Pure state machine (JVM-tested): the count-based lifecycle below is the
 * whole contract — the Android adapter (AndroidAudioFocusAdapter.kt) only
 * translates request/abandon into AudioFocusRequest calls.
 */
enum class AssistantFocusState { IDLE, DUCKING }

/** Platform bridge for the focus machine. */
interface AudioFocusAdapter {
    /** Request AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK; false = denied/failed. */
    fun requestDuckFocus(): Boolean

    fun abandonFocus()
}

/**
 * Sentence-counted duck lifecycle:
 *  - the FIRST sentence of a TTS generation requests duck focus;
 *  - the LAST drained sentence abandons it;
 *  - a flush (barge-in) abandons immediately and zeroes the count — the next
 *    generation simply re-requests (regeneration after barge-in works).
 *
 * Denial is non-fatal by design: TTS plays anyway (the old behavior), we
 * just lose the duck. Count underflow and double-abandon are clamped into
 * no-ops so racing sentence completions can never wedge the machine.
 *
 * B2 (thread safety): the callers run on the multi-threaded AppGraph scope
 * (Dispatchers.IO) with up to 3 concurrent sentence coroutines (TurnRunner
 * prefetches 2, SpeechFeedback adds a third). A plain Int counter lost
 * updates on interleaved read-modify-write, which could wedge the machine
 * in DUCKING forever (external music permanently ducked) or abandon focus
 * early. All three mutators are now serialized on one monitor.
 */
class AssistantAudioFocus(private val adapter: AudioFocusAdapter) {

    var state: AssistantFocusState = AssistantFocusState.IDLE
        private set

    private val lock = Any()

    private var activeSentences = 0

    fun onTtsSentenceStarted() = synchronized(lock) {
        activeSentences++
        if (state == AssistantFocusState.IDLE) {
            if (adapter.requestDuckFocus()) {
                state = AssistantFocusState.DUCKING
            }
            // Denied: proceed without ducking — focus is a courtesy, not a gate.
        }
    }

    fun onTtsSentenceFinished() = synchronized(lock) {
        activeSentences = (activeSentences - 1).coerceAtLeast(0)
        if (activeSentences == 0 && state == AssistantFocusState.DUCKING) {
            adapter.abandonFocus()
            state = AssistantFocusState.IDLE
        }
    }

    /** Barge-in / regeneration: kill the duck NOW; later finishes are no-ops. */
    fun onTtsFlushed() = synchronized(lock) {
        activeSentences = 0
        if (state == AssistantFocusState.DUCKING) {
            adapter.abandonFocus()
            state = AssistantFocusState.IDLE
        }
    }
}
