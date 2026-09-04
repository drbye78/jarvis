package com.jarvis.assistant.audio

/**
 * Seam over a native wake-word engine so JVM tests can inject a fake and
 * verify teardown ordering (release-race regression) and reconfigure behavior.
 *
 * Replaces the old Porcupine-only [com.jarvis.assistant.audio.PorcupineEngine]
 * so both Picovoice Porcupine and Sherpa-ONNX KWS share one contract.
 *
 * FIXPLAN B: engines are keyword-aware. [phrases] lists the keyword phrases
 * the engine was built with, in index order — [process] returns the matched
 * phrase's INDEX (the detector maps it to [com.jarvis.assistant.contracts.Detection.WakeWord]
 * or [com.jarvis.assistant.contracts.Detection.StopPhrase]) or -1 for "nothing".
 */
interface WakeWordEngine {
    /** A keyword phrase the engine can report. */
    data class Phrase(
        /** Stable id routed in the Detection (e.g. "jarvis", "stop", or the custom text). */
        val id: String,
        /** True when hearing this phrase means "cancel the current turn". */
        val isStop: Boolean,
    )

    /** Phrases this engine recognizes, in the index order [process] reports. */
    val phrases: List<Phrase>

    /** Returns the matched phrase index (>= 0) or -1 for no detection. */
    fun process(chunk: ShortArray): Int

    /** Free native resources. Must be safe to call multiple times. */
    fun release()
}
