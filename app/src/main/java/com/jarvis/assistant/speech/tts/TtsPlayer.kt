package com.jarvis.assistant.speech.tts

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** Plays PCM chunk flows on a single serialized AudioTrack actor. */
interface TtsPlayer {
    /**
     * Enqueue a PCM flow. The returned Deferred completes when the flow has
     * fully drained to the speaker, or when it is dropped/cancelled by
     * [flush] / [release].
     *
     * CALLER CANCELLATION of the Deferred (e.g. the session lane's sentence
     * timeout) also stops playback: cancelling the Deferred aborts the
     * in-flight AudioTrack writes and the Deferred settles as cancelled —
     * [com.jarvis.assistant.audio.StreamingAudioTrackPlayer] propagates the
     * cancellation to its inner writer job.
     */
    fun play(pcm: Flow<ByteArray>): Deferred<Unit>

    /**
     * Barge-in semantics: cancel the CURRENT playback AND drop every queued
     * sentence so no stale audio plays into the new session.
     */
    fun flush()

    fun release()
}
