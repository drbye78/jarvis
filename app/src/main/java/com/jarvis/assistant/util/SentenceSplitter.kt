package com.jarvis.assistant.util

/**
 * Russian-aware sentence splitting.
 *
 * A sentence boundary is '.', '!', '?', '…' or a newline. Common Russian
 * abbreviations (г., стр., т.д., и.о., …) are NOT treated as boundaries —
 * the original code flushed TTS on every '.' including abbreviation dots,
 * producing choppy robotic speech.
 */
private val ABBREVIATIONS = setOf(
    "г", "стр", "рис", "табл", "ул", "им", "см", "др", "пр", "св", "эт",
    "тд", "тп", "те", "ио", "нэ", "т.д", "т.п", "т.е", "и.о", "н.э",
    "mr", "mrs", "dr", "st", "etc", "eg", "ie",
)

fun String.splitSentences(): List<String> {
    if (isEmpty()) return emptyList()
    val result = mutableListOf<String>()
    val sb = StringBuilder()
    val n = length
    var i = 0
    while (i < n) {
        val ch = this[i]
        when {
            // Newline is a boundary but is not part of any sentence fragment.
            ch == '\n' -> {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString())
                    sb.clear()
                }
            }

            else -> {
                sb.append(ch)
                when {
                    ch == '!' || ch == '?' || ch == '…' -> {
                        result.add(sb.toString())
                        sb.clear()
                    }

                    ch == '.' -> {
                        if (!isAbbreviationAt(this, i)) {
                            result.add(sb.toString())
                            sb.clear()
                        }
                    }
                }
            }
        }
        i++
    }
    if (sb.isNotEmpty()) result.add(sb.toString())
    return result
}

// Single-letter tokens are abbreviations only when the letter is a consonant
// (в., с., к., т. …); a vowel (А., О.) is an initial and ends the sentence.
private val CONSONANTS = setOf(
    'б', 'в', 'г', 'д', 'ж', 'з', 'к', 'л', 'м', 'н',
    'п', 'р', 'с', 'т', 'ф', 'х', 'ц', 'ч', 'ш', 'щ',
)

private fun isAbbreviationAt(text: String, dotIndex: Int): Boolean {
    var start = dotIndex - 1
    while (start >= 0 && (text[start].isLetter() || text[start] == '.')) start--
    start++
    if (start >= dotIndex) return false
    val token = text.substring(start, dotIndex).lowercase().replace(".", "")
    if (token.isEmpty()) return false
    if (token.length == 1) return token[0] in CONSONANTS
    return token in ABBREVIATIONS
}

/**
 * Incremental sentence accumulator for streaming TTS. Chunks of LLM text are
 * appended; complete sentences are extracted as soon as they arrive so the
 * first audio starts as early as possible. A hard [maxChars] force-flush
 * prevents unbounded buffering of a model that never punctuates.
 */
class SentenceBuffer(
    private val maxChars: Int = 280,
    private val splitter: (String) -> List<String> = { it.splitSentences() },
) {
    private val buffer = StringBuilder()
    private var everEmitted = false

    /** Append a text delta, returning any sentences that just completed. */
    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val out = mutableListOf<String>()

        val sentences = splitter(buffer.toString())
        // All fragments except the last are complete; the last one is the
        // remainder (no boundary yet) unless the buffer itself ends on one.
        for (i in 0 until sentences.size - 1) {
            out.add(sentences[i])
        }

        if (sentences.isEmpty()) {
            buffer.clear()
            return out.also { everEmitted = true }
        }

        val remainder = sentences.last()
        // A remainder that already ends on a REAL boundary is a complete
        // sentence. B3: testing only the CHARACTER used to re-introduce the
        // abbreviation-dot flush this splitter exists to prevent — when a
        // delta ends right after "т.д." the buffer force-flushed an
        // incomplete sentence and the continuation became a separate TTS
        // chunk (choppy speech). A trailing '.' only ends the sentence when
        // it is NOT an abbreviation dot; '!', '?' and '…' always do.
        val endsOnBoundary = when (remainder.last()) {
            '.' -> !isAbbreviationAt(remainder, remainder.lastIndex)
            '!', '?', '…' -> true
            else -> false
        }
        if (remainder.length >= maxChars || endsOnBoundary) {
            // Force-flush oversized remainder / emit completed sentence.
            out.add(remainder)
            buffer.clear()
        } else {
            buffer.setLength(0)
            buffer.append(remainder)
        }

        if (out.isNotEmpty()) everEmitted = true
        return out
    }

    /** Flush whatever remains (end of stream). */
    fun flushRemaining(): String? {
        val rest = buffer.toString().trim()
        buffer.clear()
        return rest.takeIf { it.isNotEmpty() }
    }

    fun isEmpty(): Boolean = buffer.isEmpty() && !everEmitted
}
