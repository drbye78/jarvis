package com.jarvis.assistant.contracts

/**
 * Abstraction over the microphone so the pipeline can run on-device (AudioRecord)
 * or in tests/emulator (FakeAudioSource) without changing downstream code.
 */
interface AudioSource {
    fun start()
    /** Blocks until one frame is available; returns the captured samples. */
    fun read(): ShortArray
    fun stop()
}
