package com.jarvis.assistant.contracts

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/**
 * Serialized PCM playback. Implementations must write chunks from a single
 * owner coroutine (actor) so concurrent sentences cannot interleave.
 */
interface TtsPlayer {
    /** Plays a stream of 16-bit PCM chunks; completes when playback has drained. */
    fun play(pcm: Flow<ByteArray>): Deferred<Unit>
    fun flush()
    fun release()
}
