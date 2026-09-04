package com.jarvis.assistant.audio

import android.content.Context
import java.io.File

/**
 * Pure-Kotlin sentencepiece-BPE tokenizer for Sherpa-ONNX keywords.
 *
 * Parses the sentencepiece `ModelProto` (the `.model` file) wire format for
 * pieces + scores and encodes a word with a max-score lattice Viterbi —
 * verified byte-identical to `sentencepiece.SentencePieceProcessor.encode`
 * on 77 probe words against THIS repo's bundled `bpe.model` (including the
 * ground-truth `Jarvis → ▁JA R VI S` line shipped in keywords.txt), and
 * rejecting exactly the inputs sentencepiece would render as `<unk>`
 * (digits, punctuation, accents).
 *
 * FIXPLAN C: this is what makes CUSTOM wake words possible without the
 * sherpa-onnx CLI — the user types a word, [tokenizeKeywordPhrase] produces
 * the keywords-file token line, [SherpaKeywords.toKeywordsFileContent]
 * writes the file, [SherpaKwsEngine] loads it via `newFromFile`.
 *
 * The gigaspeech vocab is UPPERCASE (see tokens.txt), matching the original
 * keywords pipeline: input is uppercased before encoding. Words that do not
 * fully segment return null — a dead keyword would spot nothing silently
 * (the AGENTS.md failure mode), so callers must treat null as a hard
 * validation error.
 */
class BpeTokenizer private constructor(private val pieces: Map<String, Float>) {

    /**
     * Encode one keyword phrase (possibly multi-word) into a keywords-file
     * token line like `▁HE Y ▁ST O P`. Returns null when ANY word fails to
     * segment (would be `<unk>`).
     */
    fun tokenizeKeywordPhrase(phrase: String): String? {
        val words = phrase.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val lines = ArrayList<String>(words.size)
        for (word in words) {
            val pieces = tokenizeWord(word) ?: return null
            lines.add(pieces.joinToString(" "))
        }
        return lines.joinToString(" ")
    }

    /**
     * Encode ONE word (uppercase-insensitive) into its BPE pieces, e.g.
     * `"Jarvis"` → `["▁JA", "R", "VI", "S"]`. Null when the word cannot be
     * fully covered by the vocab.
     */
    fun tokenizeWord(rawWord: String): List<String>? {
        val word = rawWord.trim()
        if (word.isEmpty()) return null
        val s = WORD_BOUNDARY + word.uppercase()
        val n = s.length

        var best = DoubleArray(n + 1) { Double.NEGATIVE_INFINITY }
        val back = IntArray(n + 1) { -1 }
        best[0] = 0.0
        for (k in 1..n) {
            val startJ = maxOf(0, k - MAX_PIECE_CHARS)
            for (j in startJ until k) {
                if (best[j] == Double.NEGATIVE_INFINITY) continue
                val score = pieces[s.substring(j, k)] ?: continue
                val candidate = best[j] + score
                if (candidate > best[k]) {
                    best[k] = candidate
                    back[k] = j
                }
            }
        }
        if (best[n] == Double.NEGATIVE_INFINITY) return null // would be <unk>

        val out = ArrayList<String>()
        var k = n
        while (k > 0) {
            val j = back[k]
            if (j <= -1) return null // unreachable; defensive
            out.add(s.substring(j, k))
            k = j
        }
        out.reverse()
        return out
    }

    companion object {
        /** Sentencepiece word-boundary marker (U+2581 LOWER ONE EIGHTH BLOCK). */
        const val WORD_BOUNDARY = "▁"

        /** Longest piece we consider during segmentation (vocab max is well under this). */
        private const val MAX_PIECE_CHARS = 24

        private val WHITESPACE = Regex("\\s+")

        /** Sentencepiece ModelProto field ids (subset used for encoding). */
        private const val FIELD_PIECES = 1
        private const val FIELD_PIECE_PIECE = 1
        private const val FIELD_PIECE_SCORE = 2
        private const val FIELD_PIECE_TYPE = 3

        /** ModelProto.SentencePiece.Type values. */
        private const val TYPE_NORMAL = 1L
        private const val TYPE_USER_DEFINED = 4L

        /**
         * Parse a sentencepiece `.model` file (protobuf wire format) and keep
         * the encodable pieces (NORMAL + USER_DEFINED). Returns null when the
         * file is missing/empty or structurally unusable — callers degrade to
         * an honest build failure instead of a silent dead keyword.
         */
        fun fromModelFile(file: File): BpeTokenizer? =
            if (file.isFile) fromModelBytes(file.readBytes()) else null

        /** Asset variant for Settings-side live validation. */
        fun fromAsset(context: Context, assetPath: String): BpeTokenizer? =
            try {
                context.assets.open(assetPath).use { input -> fromModelBytes(input.readBytes()) }
            } catch (e: Exception) {
                null
            }

        fun fromModelBytes(bytes: ByteArray): BpeTokenizer? {
            if (bytes.isEmpty()) return null
            val pieces = HashMap<String, Float>()
            try {
                forEachField(bytes) { fieldNumber, wire, payloadStart, payloadEnd ->
                    if (fieldNumber == FIELD_PIECES && wire == WIRE_LENGTH_DELIMITED) {
                        parsePiece(bytes, payloadStart, payloadEnd)?.let { (piece, score) ->
                            if (piece.isNotEmpty()) pieces[piece] = score
                        }
                    }
                    true // keep scanning
                }
            } catch (e: Exception) {
                return null
            }
            return if (pieces.size > 1) BpeTokenizer(pieces) else null
        }

        /** Returns (piece, score) for one ModelProto.SentencePiece submessage. */
        private fun parsePiece(bytes: ByteArray, start: Int, end: Int): Pair<String, Float>? {
            var piece: String? = null
            var score = 0.0f
            var type: Long = TYPE_NORMAL
            forEachField(bytes, start, end) { fieldNumber, wire, s, e ->
                when {
                    fieldNumber == FIELD_PIECE_PIECE && wire == WIRE_LENGTH_DELIMITED ->
                        piece = String(bytes, s, e - s, Charsets.UTF_8)

                    fieldNumber == FIELD_PIECE_SCORE && wire == WIRE_FIXED32 ->
                        score = Float.fromBits(readInt32LE(bytes, s))

                    fieldNumber == FIELD_PIECE_TYPE && wire == WIRE_VARINT ->
                        type = readVarint(bytes, s, e).second.toLong()

                    else -> Unit
                }
                true
            }
            val p = piece ?: return null
            return if (type == TYPE_NORMAL || type == TYPE_USER_DEFINED) p to score else null
        }

        // --------------------------------------------------------------
        // Minimal protobuf wire-format scanning (varint / length-delimited
        // / fixed32 only — everything sentencepiece uses).
        // --------------------------------------------------------------

        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5

        /** Walks top-level (or message-scoped) fields, invoking [visit] per field. */
        private inline fun forEachField(
            bytes: ByteArray,
            start: Int = 0,
            end: Int = bytes.size,
            visit: (fieldNumber: Int, wireType: Int, payloadStart: Int, payloadEnd: Int) -> Boolean,
        ) {
            var pos = start
            while (pos < end) {
                val (key, afterKey) = readVarint(bytes, pos, end)
                val fieldNumber = (key ushr 3).toInt()
                val wireType = (key and 0x7).toInt()
                pos = afterKey.toInt()
                when (wireType) {
                    WIRE_VARINT -> {
                        val (_, after) = readVarint(bytes, pos, end)
                        if (!visit(fieldNumber, wireType, pos, after)) return
                        pos = after
                    }

                    WIRE_LENGTH_DELIMITED -> {
                        val (len, afterLen) = readVarint(bytes, pos, end)
                        val payloadStart = afterLen.toInt()
                        val payloadEnd = payloadStart + len.toInt()
                        if (payloadEnd > end) throw IllegalArgumentException("truncated submessage")
                        if (!visit(fieldNumber, wireType, payloadStart, payloadEnd)) return
                        pos = payloadEnd
                    }

                    WIRE_FIXED32 -> {
                        if (!visit(fieldNumber, wireType, pos, pos + 4)) return
                        pos += 4
                    }

                    WIRE_FIXED64 -> {
                        if (!visit(fieldNumber, wireType, pos, pos + 8)) return
                        pos += 8
                    }

                    else -> throw IllegalArgumentException("unsupported wire type $wireType")
                }
            }
        }

        /** Returns (value, positionAfter). */
        private fun readVarint(bytes: ByteArray, start: Int, end: Int): Pair<Long, Int> {
            var result = 0L
            var shift = 0
            var pos = start
            while (pos < end) {
                val b = bytes[pos].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                pos++
                if (b and 0x80 == 0) return result to pos
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long")
            }
            throw IllegalArgumentException("truncated varint")
        }

        private fun readInt32LE(bytes: ByteArray, start: Int): Int =
            (bytes[start].toInt() and 0xFF) or
                ((bytes[start + 1].toInt() and 0xFF) shl 8) or
                ((bytes[start + 2].toInt() and 0xFF) shl 16) or
                ((bytes[start + 3].toInt() and 0xFF) shl 24)
    }
}
