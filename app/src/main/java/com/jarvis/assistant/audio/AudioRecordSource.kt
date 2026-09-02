package com.jarvis.assistant.audio

import android.media.AudioFormat
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.audio.aec.AecProbe
import com.jarvis.assistant.audio.aec.MicProfile
import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.AudioSpec

/**
 * On-device microphone source backed by AudioRecord.
 *
 * Captures 16 kHz / mono / 16-bit PCM in 320-sample (20 ms) frames. [read]
 * ALWAYS returns a private copy of the internal capture buffer (audit #8:
 * the full-frame fast path used to return the reused internal array itself —
 * safe only while every downstream consumer happened to copy; any future
 * EchoCanceller implementation that retains its input would corrupt audio).
 *
 * AEC Phase A: the capture profile is [MicProfile] instead of a hard-wired
 * VOICE_RECOGNITION. HARDWARE mode captures through VOICE_COMMUNICATION and
 * attaches the platform AcousticEchoCanceler via [AecProbe] (probe outcome
 * lands in AecDiag + Settings). OFF/SOFTWARE keep the clean VOICE_RECOGNITION
 * lane (SOFTWARE cancellation happens downstream in [AudioPipeline] — a HW
 * effect on the mic would break the electrical reference's linearity).
 */
class AudioRecordSource(
    private val spec: AudioSpec = AudioSpec.MIC,
    private val profile: MicProfile = MicProfile.forMode(AecMode.OFF),
) : AudioSource {

    companion object {
        /**
         * Pure decision (m5): fail fast when the framework reports a broken
         * buffer size instead of accepting a source that can never deliver
         * audio. JVM-testable without the framework call itself.
         */
        internal fun validatedBufferSize(raw: Int, sampleRate: Int): Int {
            if (raw <= 0) {
                throw IllegalStateException(
                    "AudioRecord.getMinBufferSize returned $raw at ${sampleRate}Hz/mono/16-bit — microphone source unusable",
                )
            }
            return raw
        }
    }

    private var audioRecord: android.media.AudioRecord? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null

    // Fail fast (constructor-time) on a degenerate getMinBufferSize result.
    private val bufferSize = validatedBufferSize(
        android.media.AudioRecord.getMinBufferSize(
            spec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ),
        spec.sampleRate,
    )

    // 20 ms @ 16 kHz == 320 samples, matching the Silero VAD frame size.
    private val frameSize = (spec.sampleRate * 20) / 1000

    private val bufferA = ShortArray(frameSize)
    private val bufferB = ShortArray(frameSize)
    private var useA = true

    override fun start() {
        if (audioRecord != null) return
        val record = android.media.AudioRecord(
            profile.androidAudioSource,
            spec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != android.media.AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        if (profile.attachHardwareAec) {
            // AecProbe records the outcome (AecDiag + persisted for Settings).
            echoCanceler = AecProbe.attach(record.audioSessionId)
        }
        record.startRecording()
        audioRecord = record
    }

    override fun read(): ShortArray {
        val record = audioRecord
            ?: throw IllegalStateException("AudioRecordSource not started")
        val buf = if (useA) bufferA else bufferB
        useA = !useA
        val read = record.read(buf, 0, frameSize)
        if (read <= 0) return ShortArray(0)
        // Audit #8: always hand out a private copy — never the reused internal
        // buffer — so every downstream consumer may safely retain the frame.
        return buf.copyOf(read)
    }

    override fun stop() {
        audioRecord?.apply {
            try {
                stop()
            } finally {
                release()
            }
        }
        audioRecord = null
        echoCanceler?.apply {
            try {
                release()
            } finally {
                echoCanceler = null
            }
        }
    }
}
