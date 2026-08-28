package com.jarvis.assistant.audio

/**
 * Seam over a native wake-word engine so JVM tests can inject a fake and
 * verify teardown ordering (release-race regression) and reconfigure behavior.
 *
 * Replaces the old Porcupine-only [com.jarvis.assistant.audio.PorcupineEngine]
 * so both Picovoice Porcupine and Sherpa-ONNX KWS share one contract.
 */
interface WakeWordEngine {
    /** Returns the detected keyword index (>= 0) or -1 for no detection. */
    fun process(chunk: ShortArray): Int

    /** Free native resources. Must be safe to call multiple times. */
    fun release()
}
