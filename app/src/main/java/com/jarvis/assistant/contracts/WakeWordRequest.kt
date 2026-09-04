package com.jarvis.assistant.contracts

/**
 * Fully-resolved request describing which wake-word engine to run and how.
 * Carried through WakeWordDetector.reconfigure so a single code path can
 * swap between Picovoice Porcupine and Sherpa-ONNX KWS at runtime.
 *
 * FIXPLAN C: the Sherpa path gained custom-keyword support — the AAR's
 * `newFromFile` constructor loads models from the filesystem, so a
 * generated keywords file + the extracted (or user-supplied) model builds
 * an engine for any English keyword the bundled BPE model can tokenize.
 */
data class WakeWordRequest(
    // "porcupine" | "sherpa".
    val engine: String,
    // Porcupine .ppn path (null = built-in JARVIS keyword).
    val keywordPath: String?,
    /**
     * Sherpa-ONNX model directory. FIXPLAN C semantics:
     * - null → the model is loaded from the bundled APK assets via RELATIVE
     *   paths (Mode A, `newFromAsset`) together with the bundled keywords
     *   file. This is the zero-config default.
     * - non-blank → an absolute directory with encoder/decoder/joiner/
     *   tokens/bpe.model — either the [com.jarvis.assistant.audio.SherpaModelStore]
     *   extraction of the bundled model (needed for generated keyword files)
     *   or a user-supplied model. Loads via `newFromFile`.
     */
    val sherpaModelDir: String?,
    /**
     * Custom Sherpa wake-word TEXT (FIXPLAN C). Blank/null = the bundled
     * keywords file (jarvis + stop). Non-blank = an English word or short
     * phrase; the engine tokenizes it against the model's BPE vocab and
     * generates a keywords file (plus the stop phrase).
     */
    val sherpaCustomKeyword: String?,
    // Engine sensitivity / keyword score, 0.0-1.0.
    // - Porcupine: passed straight through as the keyword score.
    // - Sherpa-ONNX: mapped to keywordsThreshold (higher sensitivity means a
    //   lower threshold, i.e. an easier trigger). It is NOT the keyword score.
    val sensitivity: Float,
    // FIXPLAN B: when true the engine keyword set includes the stop phrase
    // (or the dedicated Porcupine-mode stop lane is armed).
    val stopPhraseEnabled: Boolean = true,
)
