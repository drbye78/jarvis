package com.jarvis.assistant.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber

/**
 * Parser for Server-Sent Events produced by OpenAI-compatible
 * chat-completions streaming endpoints (GigaChat included).
 *
 * Pure, JVM-testable, and deliberately defensive: a malformed chunk is
 * skipped (with a log line — audit #28), never fatal to the stream.
 */
object SseParser {

    /** One incremental tool-call delta, keyed by its index in the stream. */
    data class ToolDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsDelta: String,
    )

    /** The interesting parts of one SSE `data:` payload. */
    data class ParsedChunk(
        val text: String?,
        val toolDeltas: List<ToolDelta>,
        val finishReason: String?,
    )

    /**
     * Extracts the data payload of an SSE line.
     * Returns null for blank lines, comments (`:...`), and non-`data:` lines.
     */
    fun dataPayload(line: String): String? {
        if (line.isBlank()) return null
        if (line.startsWith(":")) return null
        if (!line.startsWith("data:")) return null
        return line.removePrefix("data:").trim()
    }

    fun isDone(data: String): Boolean = data == "[DONE]"

    /**
     * SSE event assembly (spec §; audit #13): one event is a run of field
     * lines terminated by a BLANK line, and all `data:` lines of one event
     * are joined with `\n`. Feeding lines one at a time returns the completed
     * payload on the terminating blank line (null otherwise); [flush] emits a
     * pending unterminated event at EOF — some servers omit the final blank
     * line, and the old line-at-a-time processing had to tolerate that.
     *
     * GigaChat/OpenAI send one `data:` line per event, so the assembled
     * payload is byte-identical to the single line — zero behavior change for
     * the common case; a server that splits a payload across multiple `data:`
     * lines is now assembled per spec instead of being silently truncated.
     */
    class EventAssembler {
        private val dataLines = ArrayList<String>(2)

        /** Feed one raw line; returns the completed event payload, or null. */
        fun offer(line: String): String? {
            if (line.isBlank()) {
                return flush()
            }
            if (line.startsWith(":")) return null // comment
            if (!line.startsWith("data:")) return null // other fields (event:, id:, retry:)
            dataLines += line.removePrefix("data:").trim()
            return null
        }

        /** Emit a pending unterminated event (EOF tolerance); null when empty. */
        fun flush(): String? {
            if (dataLines.isEmpty()) return null
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            return payload
        }
    }

    /**
     * Parses a `data:` payload JSON into a [ParsedChunk].
     * Returns null if the payload is not a JSON object we can interpret —
     * the caller should skip it.
     */
    fun parseChunk(json: Json, data: String): ParsedChunk? {
        val chunk = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
            // Audit #28: a silently swallowed parse failure made LLM response
            // corruption undiagnosable — log it (bounded length, never the raw
            // body: it can echo token-adjacent material to the file log).
            Timber.w(e, "SseParser: failed to parse chunk (%d chars)", data.length)
            return null
        }

        val choices = chunk["choices"]?.jsonArray ?: return null
        if (choices.isEmpty()) return null
        val choice = try {
            choices[0].jsonObject
        } catch (e: Exception) {
            return null
        }

        var text: String? = null
        val toolDeltas = mutableListOf<ToolDelta>()
        var finishReason: String? = null

        val delta = choice["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
        if (delta != null) {
            delta["content"]?.let { c ->
                runCatching { c.jsonPrimitive.contentOrNull }.getOrNull()?.let { text = it }
            }
            delta["tool_calls"]?.let { tcs ->
                runCatching { tcs.jsonArray }.getOrNull()?.forEach { tc ->
                    val tco = runCatching { tc.jsonObject }.getOrNull() ?: return@forEach
                    val index = tco["index"]
                        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                        ?.toIntOrNull() ?: 0
                    val id = tco["id"]
                        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    val fn = tco["function"]
                        ?.let { runCatching { it.jsonObject }.getOrNull() }
                    val name = fn?.get("name")
                        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    val args = fn?.get("arguments")
                        ?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: ""
                    toolDeltas.add(ToolDelta(index, id, name, args))
                }
            }
        }

        choice["finish_reason"]?.let { fr ->
            runCatching { fr.jsonPrimitive.contentOrNull }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { finishReason = it }
        }

        return ParsedChunk(text, toolDeltas, finishReason)
    }
}
