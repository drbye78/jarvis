package com.jarvis.assistant.model

/**
 * Read-time hygiene for leaked tool calls.
 *
 * Yandex AI Studio (provider `yandex`) occasionally renders a tool call as a
 * FENCED TEXT BLOCK in the assistant content instead of a structured
 * `function_call` item. The OBSERVED shape puts the tool name and the JSON
 * payload on SEPARATE lines:
 *
 * ```
 * getWeather
 * {"location":""}
 * ```
 *
 * Once such a turn is persisted as an ordinary assistant message
 * (`toolCallsJson = NULL`), later turns imitate the format and every tool stops
 * executing while the fenced block is spoken aloud. [strip] removes only the
 * leaked blocks so the poisoned exemplar is never fed back to the model.
 *
 * The one-line form (`getWeather {"location":""}` inside the fence) is accepted
 * too, because the model is free to reflow. Recognition is deliberately
 * SHAPE-based and does NOT require the payload to be valid JSON — the poison is
 * the shape, not the parseability. A recognized leaked-call block must satisfy
 * ALL of:
 *
 * 1. It is a COMPLETE fenced block: an opening fence of 3+ backticks (optionally
 *    followed by an info string such as `json`) and a matching closing fence of
 *    3+ backticks. An unterminated fence is never recognized.
 * 2. Its inner content trims to `<name>` + whitespace + a payload, where
 *    `<name>` is an exact, case-sensitive member of the advertised tool names.
 *    Everything after the first whitespace is joined across lines and trimmed,
 *    so a name on its own line followed by the JSON on the next one is matched.
 * 3. The payload starts with `{` and ends with `}`.
 *
 * Rule 2 is the anti-false-positive guard: a legitimate code block whose first
 * token is not an advertised tool name (`kotlin`, `val`, `{`, …) is never
 * touched, even when it is multi-line.
 *
 * Pure Kotlin — no Android and no serialization dependency — so it is safe on
 * the turn path.
 */
object ToolCallSlip {

    private const val FENCE = "```"

    /** True when [content] contains at least one recognized leaked-call block. */
    fun hasSlip(content: String, names: Set<String>): Boolean =
        content.isNotEmpty() && strip(content, names) != content

    /**
     * Removes every recognized leaked-call block; returns the remaining prose.
     * When nothing is recognized the input is returned byte-identical. Leading
     * and trailing blank lines left behind by removals are trimmed so an
     * all-block message becomes blank; prose is otherwise not reformatted.
     */
    fun strip(content: String, names: Set<String>): String {
        if (content.isEmpty() || names.isEmpty() || !content.contains(FENCE)) return content

        val lines = content.split('\n')
        val out = ArrayList<String>(lines.size)
        var removedAny = false
        var i = 0
        while (i < lines.size) {
            val openLen = openingFenceLength(lines[i])
            if (openLen != null) {
                val close = closingFenceIndex(lines, i, openLen)
                if (close > i && isLeakedCall(lines.subList(i + 1, close).joinToString("\n"), names)) {
                    // A complete recognized block: drop every line it spans.
                    removedAny = true
                    i = close + 1
                    continue
                }
            }
            val cleaned = stripInline(lines[i], names)
            if (cleaned != lines[i]) removedAny = true
            out.add(cleaned)
            i++
        }
        return if (removedAny) trimBlankEdges(out).joinToString("\n") else content
    }

    /**
     * Rules 2-3 on a block's joined inner text. The payload is the remainder
     * after the first whitespace, so the name and the JSON may sit on one line
     * or on consecutive lines.
     */
    private fun isLeakedCall(inner: String, names: Set<String>): Boolean {
        val text = inner.trim()
        if (text.isEmpty()) return false
        val split = text.indexOfFirst { it.isWhitespace() }
        if (split <= 0) return false
        if (text.substring(0, split) !in names) return false
        val payload = text.substring(split).trim()
        return payload.length >= 2 && payload.first() == '{' && payload.last() == '}'
    }

    /**
     * Index of the first standalone fence line after [openIndex] whose backtick
     * run is at least [openLen], or -1 when the block is never closed.
     */
    private fun closingFenceIndex(lines: List<String>, openIndex: Int, openLen: Int): Int {
        var j = openIndex + 1
        while (j < lines.size) {
            val len = openingFenceLength(lines[j])
            if (len != null && len >= openLen) return j
            j++
        }
        return -1
    }

    /**
     * The backtick count when [line] is a standalone fence line (an opening
     * fence, optionally with an info string, or a closing fence). Returns null
     * for prose and for the inline ` ```name {…}``` ` form, whose remainder
     * after the opening run still contains backticks.
     */
    private fun openingFenceLength(line: String): Int? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(FENCE)) return null
        val count = trimmed.takeWhile { it == '`' }.length
        return if (trimmed.substring(count).contains('`')) null else count
    }

    /** Rules 2-3 for the inline ` ```name {…}``` ` form within one line. */
    private fun stripInline(line: String, names: Set<String>): String {
        if (!line.contains(FENCE)) return line

        val sb = StringBuilder()
        var cursor = 0
        while (cursor < line.length) {
            val open = line.indexOf(FENCE, cursor)
            var openEnd = open
            if (open >= 0) {
                while (openEnd < line.length && line[openEnd] == '`') openEnd++
            }
            val close = if (open < 0) -1 else line.indexOf(FENCE, openEnd)

            // No opening fence, or no closing fence after it: keep the rest.
            if (open < 0 || close < 0) {
                sb.append(line, cursor, line.length)
                break
            }

            val openLen = openEnd - open
            var closeEnd = close
            while (closeEnd < line.length && line[closeEnd] == '`') closeEnd++
            val closeLen = closeEnd - close

            if (closeLen >= openLen && isLeakedCall(line.substring(openEnd, close), names)) {
                sb.append(line, cursor, open)
                cursor = closeEnd
            } else {
                // Keep the opening fence and keep scanning from after it.
                sb.append(line, cursor, openEnd)
                cursor = openEnd
            }
        }
        return sb.toString()
    }

    /** Drops leading/trailing blank lines; interior blank lines are kept. */
    private fun trimBlankEdges(lines: List<String>): List<String> {
        var start = 0
        var end = lines.size
        while (start < end && lines[start].isBlank()) start++
        while (end > start && lines[end - 1].isBlank()) end--
        return lines.subList(start, end)
    }
}
