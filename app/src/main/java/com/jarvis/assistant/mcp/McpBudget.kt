package com.jarvis.assistant.mcp

/**
 * Pure, deterministic caps for the MCP bridge.
 *
 * Everything here is golden-testable: the same input always yields the same
 * output, and truncation is explicit (a marker is appended) so a model can tell
 * a clipped payload from a complete one. No other MCP class is referenced.
 *
 * Order is documented at each function. This object holds no state.
 */
object McpBudget {

    /** Hard ceiling on tools advertised by a single server. */
    const val MAX_TOOLS_PER_SERVER = 24

    /** Hard ceiling on `tools/list` pages walked for one server. */
    const val MAX_LIST_PAGES = 8

    /** Hard ceiling on the total tools advertised across all servers. */
    const val MAX_ADVERTISED_TOOLS = 64

    /** Description cap, measured in characters. */
    const val MAX_DESCRIPTION_CHARS = 1024

    /** Parameter-schema cap, measured in UTF-8 bytes. */
    const val MAX_SCHEMA_BYTES = 8192

    /** Tool-result cap, measured in UTF-8 bytes. */
    const val MAX_RESULT_BYTES = 16384

    /** Appended to a description that exceeded [MAX_DESCRIPTION_CHARS]. */
    const val DESCRIPTION_TRUNCATION_MARKER = "…"

    /** Appended to a result that exceeded [MAX_RESULT_BYTES]. */
    const val RESULT_TRUNCATION_MARKER = "\n… [truncated]"

    /**
     * Keep at most [MAX_TOOLS_PER_SERVER] tools, preserving the server's
     * ENCOUNTER ORDER (the order `tools/list` returned them, page by page).
     * That order is the deterministic tie-breaker: the first N are kept and the
     * remainder is silently dropped.
     *
     * @return the kept tools paired with how many were dropped.
     */
    fun <T> capTools(tools: List<T>): Pair<List<T>, Int> {
        if (tools.size <= MAX_TOOLS_PER_SERVER) return tools to 0
        return tools.take(MAX_TOOLS_PER_SERVER) to (tools.size - MAX_TOOLS_PER_SERVER)
    }

    /**
     * Truncate [description] to [MAX_DESCRIPTION_CHARS] characters, appending
     * [DESCRIPTION_TRUNCATION_MARKER]; the result is never longer than the cap.
     */
    fun capDescription(description: String): String {
        if (description.length <= MAX_DESCRIPTION_CHARS) return description
        val keep = MAX_DESCRIPTION_CHARS - DESCRIPTION_TRUNCATION_MARKER.length
        return description.take(keep) + DESCRIPTION_TRUNCATION_MARKER
    }

    /** True when [schema] fits within [MAX_SCHEMA_BYTES] UTF-8 bytes. */
    fun isSchemaAcceptable(schema: String): Boolean =
        schema.toByteArray(Charsets.UTF_8).size <= MAX_SCHEMA_BYTES

    /**
     * Truncate [result] to [MAX_RESULT_BYTES] UTF-8 bytes on a code-point
     * boundary, appending [RESULT_TRUNCATION_MARKER]; the result is never
     * longer than the cap and never ends on a lone surrogate.
     */
    fun capResult(result: String): String {
        if (result.toByteArray(Charsets.UTF_8).size <= MAX_RESULT_BYTES) return result
        val markerBytes = RESULT_TRUNCATION_MARKER.toByteArray(Charsets.UTF_8).size
        val budget = (MAX_RESULT_BYTES - markerBytes).coerceAtLeast(0)
        val builder = StringBuilder()
        var used = 0
        for (codePoint in result.codePoints().toArray()) {
            val size = utf8Size(codePoint)
            if (used + size > budget) break
            builder.appendCodePoint(codePoint)
            used += size
        }
        return builder.toString() + RESULT_TRUNCATION_MARKER
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
