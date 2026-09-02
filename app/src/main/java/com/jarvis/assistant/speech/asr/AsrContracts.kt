package com.jarvis.assistant.speech.asr

import kotlinx.coroutines.flow.SharedFlow

/** Events emitted by an open ASR stream. */
sealed interface AsrEvent {
    /** Interim hypothesis while the user is still speaking. */
    data class Partial(val text: String) : AsrEvent

    /** End-of-utterance final transcript. */
    data class Final(val text: String) : AsrEvent

    data class Failed(val cause: Throwable) : AsrEvent
}

/**
 * A single live recognition session. Audio is pushed with [send] as it is
 * captured (true streaming — perceived latency no longer grows with
 * utterance length), and results arrive on [events].
 */
interface AsrStream {
    fun send(pcm: ByteArray)
    fun finish()
    fun cancel()
    val events: SharedFlow<AsrEvent>
}

interface StreamingAsrClient {
    suspend fun open(): AsrStream
}
