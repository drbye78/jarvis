package com.jarvis.assistant.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parser for Server-Sent Events produced by OpenAI-compatible
 * chat-completions streaming endpoints (GigaChat included).
 *
 * Pure, JVM-testable, and deliberately defensive: a malformed chunk is
 * skipped, never fatal to the stream.
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
     * Parses a `data:` payload JSON into a [ParsedChunk].
     * Returns null if the payload is not a JSON object we can interpret —
     * the caller should skip it.
     */
    fun parseChunk(json: Json, data: String): ParsedChunk? {
        val chunk = try {
            json.parseToJsonElement(data).jsonObject
        } catch (e: Exception) {
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
