package com.jarvis.assistant.contracts

// Fully-resolved request describing which wake-word engine to run and how.
// Carried through WakeWordDetector.reconfigure so a single code path can
// swap between Picovoice Porcupine and Sherpa-ONNX KWS at runtime.
data class WakeWordRequest(
    // "porcupine" | "sherpa".
    val engine: String,
    // Porcupine .ppn path (null = built-in JARVIS keyword).
    val keywordPath: String?,
    // Sherpa-ONNX model directory. UNUSED by the current AAR (v1.13.6), which
    // can only load bundled assets via RELATIVE paths (Mode A). Kept in the
    // contract for forward-compat; Sherpa always reads assets/sherpa_kws/*.
    val sherpaModelDir: String?,
    // Sherpa keyword phrase this model is tuned for (informational).
    val sherpaKeyword: String,
    // Engine sensitivity / keyword score, 0.0-1.0.
    // - Porcupine: passed straight through as the keyword score.
    // - Sherpa-ONNX: mapped to keywordsThreshold (higher sensitivity means a
    //   lower threshold, i.e. an easier trigger). It is NOT the keyword score.
    val sensitivity: Float,
)
