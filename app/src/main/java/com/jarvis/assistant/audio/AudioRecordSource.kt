package com.jarvis.assistant.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.AudioSpec

/**
 * On-device microphone source backed by [AudioRecord].
 *
 * Captures at the mic spec (16 kHz, mono, 16-bit PCM). A single consumer
 * (the [com.jarvis.assistant.audio.AudioPipeline] producer) is expected to
 * call [read]; this class is not safe for concurrent reads.
 */
class AudioRecordSource(
    private val spec: AudioSpec = AudioSpec.MIC
) : AudioSource {

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private val bufferSize = AudioRecord.getMinBufferSize(
        spec.sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    // 20 ms @ 16 kHz == 320 samples, matching the Silero VAD frame size.
    private val frameSize = (spec.sampleRate * 20) / 1000

    // Double-buffer to avoid per-frame allocation
    private val bufferA = ShortArray(frameSize)
    private val bufferB = ShortArray(frameSize)
    private var useA = true

    override fun start() {
        if (audioRecord != null) return
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            spec.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("AudioRecord failed to initialize")
        }
        // Attach AEC using the record's own session id (NOT the buffer size).
        if (AcousticEchoCanceler.isAvailable()) {
            val aec = AcousticEchoCanceler.create(record.audioSessionId)
            aec?.enabled = true
            echoCanceler = aec
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
        return if (read == frameSize) buf else buf.copyOf(read)
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
