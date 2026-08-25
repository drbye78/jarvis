package com.jarvis.assistant.contracts

/** Shared audio format contract. */
data class AudioSpec(
    val sampleRate: Int,
    val channels: Int,      // 1 = mono
    val encodingBits: Int,  // 16
) {
    companion object {
        val MIC = AudioSpec(16_000, 1, 16)
        val TTS = AudioSpec(24_000, 1, 16)
    }
}

interface AudioSource {
    fun start()
    fun read(): ShortArray
    fun stop()
}

sealed interface Detection {
    data object WakeWord : Detection
    /** Wake-word engine failed to initialize; assistant cannot listen. */
    data class DetectorError(val message: String) : Detection
}

/**
 * Lifecycle of the wake-word engine, observable synchronously. Exists because
 * a failure emitted into a replay-less SharedFlow before any subscriber is
 * simply dropped — [Failed] must be readable at any time (M1).
 */
sealed interface DetectorState {
    data object Bootstrapping : DetectorState
    data object Ready : DetectorState
    data class Failed(val reason: String) : DetectorState

    /** Terminal: engine released; restart flows must re-init, not reuse. */
    data object Released : DetectorState
}

interface WakeWordDetector {
    val state: kotlinx.coroutines.flow.StateFlow<DetectorState>
    fun detections(): kotlinx.coroutines.flow.Flow<Detection>
    fun release()
}
