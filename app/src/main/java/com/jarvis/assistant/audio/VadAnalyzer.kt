package com.jarvis.assistant.audio

import android.content.Context
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import com.konovalov.vad.silero.VadSilero
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.SpeechDetector
import com.jarvis.assistant.util.SampleAccumulator
import com.jarvis.assistant.util.toByteArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Silero VAD based [SpeechDetector].
 *
 * [collectSpeech] first drains [ringBuffer] (so frames emitted before the
 * subscriber attached are not lost — fixes the "clipped first word" gap), then
 * collects from [frames] until [silenceFrames] consecutive silent frames are
 * seen. Returns concatenated 16-bit little-endian PCM.
 *
 * Uses [SampleAccumulator] + [ByteArrayOutputStream] to avoid per-frame array
 * allocation churn during re-chunking (320-sample mic frames → 512-sample VAD
 * frames) and PCM accumulation.
 */
class VadAnalyzer(
    private val context: Context,
    private val config: JarvisConfig = JarvisConfig(),
    private val sampleRate: Int = 16_000
) : SpeechDetector {

    private companion object {
        private const val VAD_FRAME_SAMPLES = 512
    }

    override suspend fun collectSpeech(
        frames: Flow<ShortArray>,
        ringBuffer: AudioRingBuffer,
        silenceFrames: Int
    ): ByteArray = coroutineScope {
        val vad = VadSilero(
            context,
            sampleRate = SampleRate.SAMPLE_RATE_16K,
            frameSize = FrameSize.FRAME_SIZE_512,
            mode = Mode.VERY_AGGRESSIVE,
            silenceDurationMs = config.vadSilenceDurationMs,
            speechDurationMs = config.vadSpeechDurationMs
        )

        val accumulator = SampleAccumulator(VAD_FRAME_SAMPLES)
        val pcmBytes = ByteArrayOutputStream()
        var silentCount = 0
        var speechStarted = false

        fun feed(chunk: ShortArray) {
            val bytes = chunk.toByteArray()
            val isSpeech = vad.isSpeech(bytes)
            if (isSpeech) {
                speechStarted = true
                silentCount = 0
                pcmBytes.write(bytes)
            } else if (speechStarted) {
                // Keep trailing silence so the utterance boundary is natural.
                silentCount++
                pcmBytes.write(bytes)
            }
        }

        // 1) Recover frames emitted before this collector attached.
        ringBuffer.drain().forEach { frame ->
            accumulator.append(frame)
            var chunk = accumulator.take()
            while (chunk != null) { feed(chunk); chunk = accumulator.take() }
        }

        // 2) Collect live frames until enough silence accumulates.
        if (silentCount < silenceFrames) {
            var collector: Job? = null
            collector = launch {
                frames.collect { frame ->
                    accumulator.append(frame)
                    var chunk = accumulator.take()
                    while (chunk != null) { feed(chunk); chunk = accumulator.take() }
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

        val result = pcmBytes.toByteArray()
        try {
            vad.close()
        } catch (e: Exception) {
            Timber.w(e, "VAD close failed — result already captured")
        }
        result
    }
}