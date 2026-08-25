package com.jarvis.assistant.speech.tts

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** Plays PCM chunk flows on a single serialized AudioTrack actor. */
interface TtsPlayer {
    /**
     * Enqueue a PCM flow. The returned Deferred completes when the flow has
     * fully drained to the speaker, or when it is dropped/cancelled by
     * [flush] / [release].
     */
    fun play(pcm: Flow<ByteArray>): Deferred<Unit>

    /**
     * Barge-in semantics: cancel the CURRENT playback AND drop every queued
     * sentence so no stale audio plays into the new session.
     */
    fun flush()

    fun release()
}
