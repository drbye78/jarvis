package com.jarvis.assistant.audio

import android.content.Context
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import com.konovalov.vad.silero.VadSilero
import com.jarvis.assistant.contracts.RingBuffer
import com.jarvis.assistant.contracts.SpeechDetector
import com.jarvis.assistant.util.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Silero VAD based [SpeechDetector].
 *
 * [collectSpeech] first drains [ringBuffer] (so frames emitted before the
 * subscriber attached are not lost — fixes the "clipped first word" gap), then
 * collects from [frames] until [silenceFrames] consecutive silent frames are
 * seen. Returns concatenated 16-bit little-endian PCM.
 *
 * The mic pipeline emits 320-sample (20 ms) frames, but Silero VAD requires a
 * fixed 512-sample frame, so we buffer and re-chunk before classifying.
 */
class VadAnalyzer(
    private val context: Context,
    private val sampleRate: Int = 16_000
) : SpeechDetector {

    private companion object {
        private const val VAD_FRAME_SAMPLES = 512
    }

    override suspend fun collectSpeech(
        frames: Flow<ShortArray>,
        ringBuffer: RingBuffer<ShortArray>,
        silenceFrames: Int
    ): ByteArray = coroutineScope {
        val vad = VadSilero(
            context,
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_512,
            mode = Mode.VERY_AGGRESSIVE,
            silenceDurationMs = 300,
            speechDurationMs = 50
        )

        val collected = mutableListOf<Byte>()
        var silentCount = 0
        var speechStarted = false
        var buffer = ShortArray(0)

        // Feed one 512-sample VAD frame: classify and accumulate speech PCM.
        fun feed(chunk: ShortArray) {
            val bytes = chunk.toByteArray()
            val isSpeech = vad.isSpeech(bytes)
            if (isSpeech) {
                speechStarted = true
                silentCount = 0
                collected.addAll(bytes.toList())
            } else if (speechStarted) {
                // Keep trailing silence so the utterance boundary is natural.
                silentCount++
                collected.addAll(bytes.toList())
            }
        }

        // Buffer incoming 320-sample frames into 512-sample VAD frames.
        fun handle(frame: ShortArray) {
            buffer = buffer + frame
            while (buffer.size >= VAD_FRAME_SAMPLES) {
                val chunk = buffer.copyOfRange(0, VAD_FRAME_SAMPLES)
                buffer = buffer.copyOfRange(VAD_FRAME_SAMPLES, buffer.size)
                feed(chunk)
            }
        }

        // 1) Recover frames emitted before this collector attached.
        ringBuffer.drain().forEach { handle(it) }

        // 2) Collect live frames until enough silence accumulates.
        if (silentCount < silenceFrames) {
            var collector: Job? = null
            collector = launch {
                frames.collect { frame ->
                    handle(frame)
                    if (speechStarted && silentCount >= silenceFrames) {
                        collector?.cancel() // stop collecting once the utterance ends
                    }
                }
            }
            try {
                collector?.join()
            } catch (_: CancellationException) {
                // Expected: collection was cancelled once enough silence was seen.
            }
        }

        vad.close()
        collected.toByteArray()
    }
}
