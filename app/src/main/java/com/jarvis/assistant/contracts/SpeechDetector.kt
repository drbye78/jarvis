package com.jarvis.assistant.contracts

import kotlinx.coroutines.flow.Flow

/**
 * Voice-activity detector. Subscribes to the shared mic [frames] flow until
 * [silenceFrames] consecutive silent frames are seen, returning 16-bit PCM at
 * the mic sample rate.
 *
 * Implementations must drain [ringBuffer] first so frames emitted before the
 * subscriber attached are not lost (fixes the "clipped first word" gap).
 */
interface SpeechDetector {
    suspend fun collectSpeech(
        frames: Flow<ShortArray>,
        ringBuffer: RingBuffer<ShortArray>,
        silenceFrames: Int = 25
    ): ByteArray
}
