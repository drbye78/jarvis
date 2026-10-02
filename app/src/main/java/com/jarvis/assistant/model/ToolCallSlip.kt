package com.jarvis.assistant.model

/**
 * Read-time hygiene for leaked tool calls.
 *
 * Yandex AI Studio (provider `yandex`) occasionally renders a tool call as a
 * FENCED TEXT BLOCK in the assistant content instead of a structured
 * `function_call` item:
 *
 * ```
 * getWeather {"location":""}
 * ```
 *
 * Once such a turn is persisted as an ordinary assistant message
 * (`toolCallsJson = NULL`), later turns imitate the format and every tool stops
 * executing while the fenced block is spoken aloud. [strip] removes only the
 * leaked blocks so the poisoned exemplar is never fed back to the model.
 *
 * Recognition is deliberately SHAPE-based and does NOT require the payload to
 * be valid JSON — the poison is the shape, not the parseability. A recognized
 * leaked-call block must satisfy ALL of:
 *
 * 1. It is a COMPLETE fenced block: an opening fence of 3+ backticks (optionally
 *    followed by an info string such as `json`) and a matching closing fence of
 *    3+ backticks. An unterminated fence is never recognized.
 * 2. The ENTIRE inner payload is a SINGLE logical line (no interior newline).
 *    This is the decisive anti-false-positive guard: a legitimate multi-line
 *    code-block answer must never be touched.
 * 3. That line splits on the FIRST whitespace into `<name>` + `<payload>`, and
 *    `<name>` is an exact, case-sensitive member of the advertised tool names.
 * 4. `<payload>` starts with `{` and ends with `}`.
 *
 * The inline form ` ```getWeather {"location":""}``` ` (single line, no
 * newlines) is accepted under the same rules 2-4.
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
            if (openLen != null && i + 2 < lines.size) {
                val closeLen = openingFenceLength(lines[i + 2])
                if (closeLen != null && closeLen >= openLen &&
                    isLeakedCall(lines[i + 1], names)
                ) {
                    // Rules 1-4 hold: drop the three-line block entirely.
                    removedAny = true
                    i += 3
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
     * Rules 3-4 applied to a single trimmed line: `<name> <jsonish payload>`.
     * Rule 2 (no interior newline) is implicit — [line] is one line by
     * construction — but is re-checked defensively.
     */
    private fun isLeakedCall(line: String, names: Set<String>): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.contains('\n')) return false
        val split = trimmed.indexOfFirst { it.isWhitespace() }
        if (split <= 0 || split == trimmed.length - 1) return false
        if (trimmed.substring(0, split) !in names) return false
        val payload = trimmed.substring(split + 1).trim()
        return payload.length >= 2 && payload.first() == '{' && payload.last() == '}'
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

    /** Rule 2 + 3 + 4 for the inline ` ```name {…}``` ` form within one line. */
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
