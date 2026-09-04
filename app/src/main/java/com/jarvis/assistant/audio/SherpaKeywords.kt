package com.jarvis.assistant.audio

/**
 * Canonical Sherpa-ONNX keyword definitions shared by the engine, the asset
 * files and the graph wiring (single source of truth — no call site may
 * invent its own token line).
 *
 * Token lines are BPE segmentations produced by sentencepiece with THIS
 * repo's `sherpa_kws/bpe.model` (the same toolchain that produced the
 * original `▁JA R VI S` line — verified byte-identical on 77 probe words,
 * see [BpeTokenizer]). The keywords-file format is sherpa-onnx's
 * `tokens : threshold # boost` per line.
 *
 * The stop phrase is the English word "stop" — acoustically the SAME word
 * as Russian «стоп» (/stɒp/), which is why the English-BPE gigaspeech model
 * can spot it: FIXPLAN B's voice stop works in both product languages with
 * zero extra models.
 */
object SherpaKeywords {
    /** Id routed in [com.jarvis.assistant.contracts.Detection] for the wake phrase. */
    const val WAKE_ID = "jarvis"

    /** BPE("Jarvis".uppercase()) — must stay identical to assets/sherpa_kws/keywords.txt. */
    const val WAKE_TOKEN_LINE = "▁JA R VI S"

    /** Id routed for the stop phrase (FIXPLAN B). */
    const val STOP_ID = "stop"

    /** BPE("stop".uppercase()) — must stay identical to the shipped asset lines. */
    const val STOP_TOKEN_LINE = "▁ST O P"

    /** Per-keyword score/boost written into generated files (matches the bundled asset). */
    const val KEYWORDS_SCORE = 1.5f
    const val KEYWORDS_BOOST = 0.25f

    /** Asset-relative locations. */
    const val ASSET_DIR = "sherpa_kws"
    const val ASSET_KEYWORDS_FILE = "$ASSET_DIR/keywords.txt"
    const val ASSET_KEYWORDS_STOP_FILE = "$ASSET_DIR/keywords_stop.txt"

    /** One keyword as the engine/detector see it. */
    data class Entry(
        val tokenLine: String,
        val id: String,
        val isStop: Boolean,
    )

    /** The wake phrase entry. */
    fun wake(): Entry = Entry(WAKE_TOKEN_LINE, WAKE_ID, isStop = false)

    /** The stop phrase entry (FIXPLAN B). */
    fun stop(): Entry = Entry(STOP_TOKEN_LINE, STOP_ID, isStop = true)

    /**
     * Render entries into a keywords-file body: one `tokens : score # boost`
     * line per entry. Pure — unit-tested against the shipped asset files.
     */
    fun toKeywordsFileContent(entries: List<Entry>): String =
        entries.joinToString(separator = "\n", postfix = "\n") { e ->
            "${e.tokenLine} :${KEYWORDS_SCORE} #${KEYWORDS_BOOST}"
        }

    /**
     * Parse a keywords-file body back into entries (used by tests and by the
     * bundled-asset engine path to align file lines with phrase ids).
     * Tolerates both `:1.5` and `: 1.5` spacing; ignores comments/blank lines.
     */
    fun parseKeywordsFileContent(content: String): List<String> =
        content.lines()
            .map { it.substringBefore(':').trim() }
            .filter { it.isNotEmpty() }
}
