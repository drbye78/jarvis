package com.jarvis.assistant.contracts

/**
 * Shared audio format contract.
 * - MIC: capture path (16 kHz, used by WakeWord / VAD / ASR).
 * - TTS: playback path (24 kHz, produced by SaluteSpeechTTS, consumed by StreamingAudioTrackPlayer).
 */
data class AudioSpec(
    val sampleRate: Int,
    val channels: Int,     // 1 = mono
    val encodingBits: Int // 16
) {
    companion object {
        val MIC = AudioSpec(16_000, 1, 16)
        val TTS = AudioSpec(24_000, 1, 16)
    }
}
