package com.jarvis.assistant.util

/**
 * Russian-aware sentence boundary helpers used to flush TTS per sentence.
 *
 * A sentence boundary is one of: '.', '!', '?', the ellipsis character '…',
 * or a newline. Delimiters are kept attached to the preceding fragment so the
 * TTS voice still renders natural pauses.
 *
 * "Russian-aware" means we avoid splitting on common abbreviated tokens such as
 * "г.", "стр.", "т.д.", "и.о." etc. (single-letter abbreviations and a small
 * known set). This is a pragmatic guard, not a full grammar parser.
 */
object SentenceSplitter {

    private val ABBREVIATIONS = setOf(
        "г", "стр", "рис", "табл", "ул", "им", "см", "др", "пр", "св", "эт",
        "тд", "тп", "те", "ио", "нэ", "т.д", "т.п", "т.е", "и.о", "н.э"
    )

    /** True when this string ends on a sentence boundary. */
    fun String.endsWithSentence(): Boolean {
        if (isEmpty()) return false
        return when (last()) {
            '.', '!', '?', '…', '\n' -> true
            else -> false
        }
    }

    /**
     * Splits into sentence fragments, keeping the boundary delimiter at the end
     * of each fragment. Trailing text without a boundary is returned as the last
     * fragment.
     */
    fun String.splitSentences(): List<String> {
        if (isEmpty()) return emptyList()
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        val n = length
        var i = 0
        while (i < n) {
            val ch = this[i]
            sb.append(ch)
            when {
                ch == '!' || ch == '?' || ch == '…' || ch == '\n' -> {
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
            i++
        }
        if (sb.isNotEmpty()) result.add(sb.toString())
        return result
    }

    /**
     * Returns true if the '.' at [dotIndex] belongs to an abbreviation token
     * (e.g. "г." or "т.д.") and therefore should NOT be treated as a sentence
     * boundary.
     *
     * The backward walk is capped at [MAX_ABBREV_TOKEN_LEN] characters: any
     * longer run cannot be a known abbreviation, and without the cap a long
     * letter/dot run (e.g. LLM-emitted "а.б.в…") degrades to O(n²).
     */
    private fun isAbbreviationAt(text: String, dotIndex: Int): Boolean {
        var start = dotIndex - 1
        var walked = 0
        while (start >= 0 && walked < MAX_ABBREV_TOKEN_LEN &&
            (text[start].isLetter() || text[start] == '.')
        ) {
            start--
            walked++
        }
        start++ // first char of the token
        if (start >= dotIndex) return false
        val token = text.substring(start, dotIndex).lowercase().replace(".", "")
        if (token.isEmpty()) return false
        if (token.length == 1) return true // single-letter abbreviation, e.g. "г."
        return token in ABBREVIATIONS
    }

    private const val MAX_ABBREV_TOKEN_LEN = 12
}
