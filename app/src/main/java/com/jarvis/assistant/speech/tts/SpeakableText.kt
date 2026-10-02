package com.jarvis.assistant.speech.tts

import kotlin.math.abs

/**
 * Pure, Android-free normalizer applied ONLY at the TTS boundary.
 *
 * The persisted/displayed assistant transcript always keeps the ORIGINAL
 * text — this object exists so speech synthesis never reads markdown
 * markup, emoji or unresolved symbols out loud. It is deliberately
 * conservative: when a pattern is ambiguous the text is left untouched
 * (a conservative transform beats an aggressive one).
 *
 * Regexes are applied in a fixed order (markup first, symbols last) so an
 * earlier pass can never corrupt the input of a later one. No state, no
 * logging, no Android types.
 */
object SpeakableText {

    /** Strong emphasis is unwrapped BEFORE the single-character variants. */
    private val STRONG_ASTERISK = Regex("""\*\*(.+?)\*\*""")
    private val STRONG_UNDERSCORE = Regex("""__(.+?)__""")

    /**
     * Paired single delimiters only. The lookarounds around the markers
     * keep `snake_case` identifiers and `2*3` arithmetic intact: a marker
     * that is glued to a letter/digit on the outside is not emphasis.
     */
    private val EMPHASIS_ASTERISK =
        Regex("""(?<![\p{L}\p{N}])\*(?!\s)(.+?)(?<!\s)\*(?![\p{L}\p{N}])""")
    private val EMPHASIS_UNDERSCORE =
        Regex("""(?<![\p{L}\p{N}])_(?!\s)(.+?)(?<!\s)_(?![\p{L}\p{N}])""")

    /** Fenced code keeps its inner text; the fence markers are dropped. */
    private val FENCED_CODE =
        Regex("""```(?:[\p{L}\p{N}_+\-]*)?\n?(.*?)```""", RegexOption.DOT_MATCHES_ALL)
    private val UNCLOSED_FENCE = Regex("""```.*""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_CODE = Regex("""`([^`]+)`""")
    private val STRAY_BACKTICK = Regex("`")

    private val MARKDOWN_LINK = Regex("""\[([^\]]*)\]\([^)]*\)""")
    private val BARE_URL = Regex("""https?://\S+|www\.\S+""")

    /** List markers: bullet or ordered. The trailing capture is the item. */
    private val LIST_MARKER = Regex("""(?:[-*•–—]|\d+[.)])\s+(.+)""")
    private val HEADING = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val BLOCKQUOTE = Regex("""^>\s?""", RegexOption.MULTILINE)
    private val CHECKBOX = Regex("""\[[ xX]\]""")

    private val EMOJI = Regex(
        """[\u2600-\u27BF\u2B00-\u2BFF\u2190-\u21FF\u2300-\u23FF\uFE0F\u200D""" +
            """\x{1F000}-\x{1FAFF}\x{1F1E6}-\x{1F1FF}]""",
    )
    private val ZERO_WIDTH = Regex("""[\u200B-\u200F\u200D\u2060\uFEFF]""")

    private val MULTI_BLANK = Regex("""\n{3,}""")
    private val MULTI_SPACE = Regex("""[ \t]{2,}""")
    private val ELLIPSIS = Regex("…")

    private val DEGREE_SIGN =
        Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s*°\s*([CFсС])(?![\p{L}\p{N}_])""")
    private val BARE_DEGREE =
        Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s+([CF])(?![\p{L}\p{N}_])""")
    private val PERCENT = Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s*%""")
    private val KMH = Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s*км\s*/\s*ч(?![\p{L}\p{N}_])""")
    private val MS = Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s*м\s*/\s*с(?![\p{L}\p{N}_])""")
    private val GRAM = Regex("""([+\-−]?\d+(?:[.,]\d+)?)\s*г(?![\p{L}\p{N}_])""")
    private val LEADING_MINUS = Regex("""(^|[\s(])[-−](?=\d)""")

    private val DEGREE_FORMS = PluralForms("градус", "градуса", "градусов")
    private val PERCENT_FORMS = PluralForms("процент", "процента", "процентов")
    private val KILOMETRE_FORMS = PluralForms("километр", "километра", "километров")
    private val METRE_FORMS = PluralForms("метр", "метра", "метров")
    private val CENTIMETRE_FORMS = PluralForms("сантиметр", "сантиметра", "сантиметров")
    private val MILLIMETRE_FORMS = PluralForms("миллиметр", "миллиметра", "миллиметров")
    private val KILOGRAM_FORMS = PluralForms("килограмм", "килограмма", "килограммов")
    private val GRAM_FORMS = PluralForms("грамм", "грамма", "граммов")

    /** A quoted line longer than this is prose, not a quoted title. */
    private const val MAX_WRAPPED_LINE = 120

    /**
     * Return the spoken form of [text]: markup removed, symbols expanded.
     * Pure and idempotent for already-clean text (a sentence with no
     * markup and no units is returned byte-identical).
     */
    fun prepare(text: String): String {
        if (text.isEmpty()) return text
        var result = stripCode(text)
        result = stripLinks(result)
        result = joinListLines(result)
        result = stripBlockMarkup(result)
        result = stripEmphasis(result)
        result = stripInvisible(result)
        result = normalizeWhitespace(result)
        result = normalizeQuotesAndEllipsis(result)
        result = expandSymbols(result)
        return result.trim()
    }

    private fun stripCode(text: String): String {
        var result = FENCED_CODE.replace(text) { it.groupValues[1] }
        result = UNCLOSED_FENCE.replace(result, "")
        result = INLINE_CODE.replace(result) { it.groupValues[1] }
        return STRAY_BACKTICK.replace(result, "")
    }

    private fun stripLinks(text: String): String {
        val result = MARKDOWN_LINK.replace(text) { it.groupValues[1] }
        // Spoken URLs are useless noise; drop them entirely.
        return BARE_URL.replace(result, "")
    }

    /**
     * Turn list blocks into one spoken run. Each item keeps its text; items
     * are comma-joined and terminated with a period so TTS does not run the
     * lines together. Non-list lines and blank lines pass through untouched
     * (whitespace normalization later collapses blanks).
     */
    private fun joinListLines(text: String): String {
        val out = mutableListOf<String>()
        val items = mutableListOf<String>()
        fun flush() {
            if (items.isEmpty()) return
            val joined = items.joinToString(", ") { it.trim().trimEnd(',', ';', ':').trim() }
                .trimEnd(',', ';', ':')
            val endsSentence = joined.endsWith(".") || joined.endsWith("!") || joined.endsWith("?")
            out.add(if (endsSentence) joined else joined + ".")
            items.clear()
        }
        for (raw in text.split('\n')) {
            val line = raw.trim()
            val marker = LIST_MARKER.matchEntire(line)
            if (marker != null) {
                items.add(marker.groupValues[1])
            } else {
                flush()
                out.add(line)
            }
        }
        flush()
        return out.joinToString("\n")
    }

    private fun stripBlockMarkup(text: String): String {
        var result = HEADING.replace(text, "")
        result = BLOCKQUOTE.replace(result, "")
        return CHECKBOX.replace(result, "")
    }

    private fun stripEmphasis(text: String): String {
        var result = STRONG_ASTERISK.replace(text) { it.groupValues[1] }
        result = STRONG_UNDERSCORE.replace(result) { it.groupValues[1] }
        result = EMPHASIS_ASTERISK.replace(result) { it.groupValues[1] }
        return EMPHASIS_UNDERSCORE.replace(result) { it.groupValues[1] }
    }

    private fun stripInvisible(text: String): String {
        var result = EMOJI.replace(text, "")
        result = ZERO_WIDTH.replace(result, "")
        return result
    }

    private fun normalizeWhitespace(text: String): String {
        var result = MULTI_BLANK.replace(text, "\n\n")
        result = result.lines().joinToString("\n") { it.trim() }
        return MULTI_SPACE.replace(result, " ")
    }

    private fun normalizeQuotesAndEllipsis(text: String): String {
        val unwrapped = text.lines().joinToString("\n") { unwrapQuotes(it) }
        return ELLIPSIS.replace(unwrapped, ".")
    }

    /**
     * Strip «…» / “…” / "…" only when they wrap an entire SHORT line, and
     * only for a cleanly paired straight-quote line. Inline quoting and
     * longer prose keep their punctuation.
     */
    private fun unwrapQuotes(line: String): String {
        val trimmed = line.trim()
        if (trimmed.length < 2 || trimmed.length > MAX_WRAPPED_LINE) return line
        val paired = (trimmed.first() == '«' && trimmed.last() == '»') ||
            (trimmed.first() == '“' && trimmed.last() == '”') ||
            (trimmed.first() == '"' && trimmed.last() == '"' && trimmed.count { it == '"' } == 2)
        return if (paired) trimmed.substring(1, trimmed.length - 1).trim() else line
    }

    private fun expandSymbols(text: String): String {
        var result = DEGREE_SIGN.replace(text) { temperaturePhrase(it) }
        result = BARE_DEGREE.replace(result) { temperaturePhrase(it) }
        result = PERCENT.replace(result) { pluralPhrase(it.groupValues[1], PERCENT_FORMS) }
        result = KMH.replace(result) { numberSpoken(it.groupValues[1]) + " километров в час" }
        result = MS.replace(result) { numberSpoken(it.groupValues[1]) + " метров в секунду" }
        result = expandUnit(result, "км", KILOMETRE_FORMS)
        result = expandUnit(result, "см", CENTIMETRE_FORMS)
        result = expandUnit(result, "мм", MILLIMETRE_FORMS)
        result = expandUnit(result, "кг", KILOGRAM_FORMS)
        result = expandUnit(result, "м", METRE_FORMS)
        result = expandGram(result)
        return normalizeLeadingMinus(result)
    }

    /**
     * Expand `<number> <unit>` into the plural Russian noun. The unit must
     * sit immediately after a number (only whitespace between) and must not
     * be followed by a letter/digit — so ordinary words such as «масло» or
     * «гора» are never touched.
     */
    private fun expandUnit(text: String, unit: String, forms: PluralForms): String {
        val regex = Regex(
            "([+\\-−]?\\d+(?:[.,]\\d+)?)\\s*" + Regex.escape(unit) + "(?![\\p{L}\\p{N}_])",
        )
        return regex.replace(text) { m -> pluralPhrase(m.groupValues[1], forms) }
    }

    /** `г` is guarded: a 4+ digit year («2024 г.») is a year, not grams. */
    private fun expandGram(text: String): String = GRAM.replace(text) { m ->
        val number = m.groupValues[1]
        if (number.count { it.isDigit() } >= 4) m.value else pluralPhrase(number, GRAM_FORMS)
    }

    /** A leading `-`/`−` on a number becomes the spoken word «минус». */
    private fun normalizeLeadingMinus(text: String): String =
        LEADING_MINUS.replace(text) { it.groupValues[1] + "минус " }

    private fun temperaturePhrase(match: MatchResult): String {
        val scale = if (match.groupValues[2].equals("F", ignoreCase = true)) {
            "Фаренгейта"
        } else {
            "Цельсия"
        }
        return pluralPhrase(match.groupValues[1], DEGREE_FORMS) + " " + scale
    }

    private fun pluralPhrase(rawNumber: String, forms: PluralForms): String {
        val value = rawNumber.replace(',', '.').trimStart('+', '-', '−').toDoubleOrNull()
            ?: return rawNumber
        return numberSpoken(rawNumber) + " " + russianPlural(value, forms)
    }

    /** Digits with the leading sign spoken out; decimal comma is kept. */
    private fun numberSpoken(raw: String): String {
        val trimmed = raw.trim()
        val sign = when {
            trimmed.startsWith("-") || trimmed.startsWith("−") -> "минус "
            trimmed.startsWith("+") -> "плюс "
            else -> ""
        }
        val digits = trimmed.dropWhile { it == '+' || it == '-' || it == '−' }
        return sign + digits
    }

    /**
     * Russian plural rule by the last two digits. Fractional values take the
     * genitive-singular form («19,5 градуса»), matching spoken Russian.
     */
    private fun russianPlural(value: Double, forms: PluralForms): String {
        val absolute = abs(value)
        if (absolute % 1.0 != 0.0) return forms.few
        val n = absolute.toInt()
        return when {
            n % 100 in 11..14 -> forms.many
            n % 10 == 1 -> forms.one
            n % 10 in 2..4 -> forms.few
            else -> forms.many
        }
    }

    private data class PluralForms(val one: String, val few: String, val many: String)
}
