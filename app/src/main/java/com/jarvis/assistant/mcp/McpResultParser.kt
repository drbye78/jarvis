package com.jarvis.assistant.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure JSON-RPC ↔ MCP result parsing. No OkHttp and no Android here, so every
 * rule in this file is JVM-testable without a socket. The transport supplies
 * the bytes and the `Content-Type`; this property decides what they MEAN.
 */
object McpResultParser {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Extracts the JSON-RPC envelope from plain JSON or an SSE stream. SSE
     * frames carry the JSON on `data:` lines and the LAST such frame is the
     * terminal message; a leading `event:` line and trailing blank lines are
     * tolerated. A body that is valid JSON object text wins regardless of the
     * declared content type.
     */
    fun parseEnvelope(payload: String, contentType: String?): McpEnvelope {
        val text = extractJson(payload, contentType) ?: return McpEnvelope.Malformed
        val envelope = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return McpEnvelope.Malformed
        return toEnvelope(envelope)
    }

    /** The tool-execution result of a `tools/call` envelope. */
    fun parseToolResult(envelope: McpEnvelope): McpToolResult2 = when (envelope) {
        is McpEnvelope.ProtocolError -> McpToolResult2("${envelope.code}: ${envelope.message}", isError = true)
        McpEnvelope.Malformed -> McpToolResult2("Malformed MCP reply", isError = true)
        is McpEnvelope.Result -> toolResult(envelope.payload)
    }

    /**
     * The paged `tools/list` result: `result.tools[]` descriptors plus
     * `result.nextCursor`. A non-result envelope yields an empty page — the
     * transport classifies that case before it ever reaches here.
     */
    fun parseToolsList(envelope: McpEnvelope): McpToolsPage {
        val result = (envelope as? McpEnvelope.Result)
            ?.payload?.get("result")?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return McpToolsPage(emptyList(), null)
        val tools = result["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull(::toolDescriptor)
            .orEmpty()
        val cursor = result["nextCursor"]?.jsonPrimitive?.contentOrNull
        return McpToolsPage(tools, cursor)
    }

    private fun extractJson(payload: String, contentType: String?): String? {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return null
        return if (isEventStream(contentType)) {
            sseJson(payload) ?: trimmed.takeIf { it.startsWith("{") }
        } else {
            trimmed.takeIf { it.startsWith("{") } ?: sseJson(payload)
        }
    }

    private fun isEventStream(contentType: String?): Boolean =
        contentType?.contains("text/event-stream", ignoreCase = true) == true

    /** Last `data:` frame wins — that is the terminal JSON-RPC message. */
    private fun sseJson(payload: String): String? = payload
        .lineSequence()
        .filter { it.startsWith(SSE_DATA_PREFIX) }
        .map { it.removePrefix(SSE_DATA_PREFIX).trim() }
        .filter { it.startsWith("{") }
        .lastOrNull()

    private fun toEnvelope(envelope: JsonObject): McpEnvelope {
        val error = envelope["error"]
        if (error != null) {
            val obj = runCatching { error.jsonObject }.getOrNull()
            val code = obj?.get("code")?.jsonPrimitive?.intOrNull
            if (obj == null || code == null) return McpEnvelope.Malformed
            val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            return McpEnvelope.ProtocolError(code, message, obj["data"]?.toString())
        }
        return if (envelope.containsKey("result")) McpEnvelope.Result(envelope) else McpEnvelope.Malformed
    }

    private fun toolResult(envelope: JsonObject): McpToolResult2 {
        val result = envelope["result"]?.let { runCatching { it.jsonObject }.getOrNull() }
            ?: return McpToolResult2("Malformed MCP reply", isError = true)
        // A `tools/list` reply is discovery, never a `tools/call` result; it
        // must not masquerade as a successful empty tool result.
        if (result.containsKey("tools")) {
            return McpToolResult2(TOOLS_LIST_AS_TOOL_RESULT, isError = true)
        }
        val isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        // structuredContent is the machine-readable copy; fall back to the text block.
        result["structuredContent"]?.let { structured ->
            return McpToolResult2(stringify(structured), isError)
        }
        val text = result["content"]?.let { content ->
            runCatching {
                content.jsonArray
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("")
            }.getOrNull()
        }
        return McpToolResult2(text ?: "Empty MCP reply", isError)
    }

    private fun toolDescriptor(element: JsonElement): McpToolDescriptor? {
        val obj = runCatching { element.jsonObject }.getOrNull() ?: return null
        val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return McpToolDescriptor(
            name = name,
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            inputSchema = obj["inputSchema"]?.let(::stringify).orEmpty(),
        )
    }

    private fun stringify(element: JsonElement): String =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: element.toString()

    private const val SSE_DATA_PREFIX = "data:"
    private const val TOOLS_LIST_AS_TOOL_RESULT =
        "Malformed MCP reply: expected a tools/call result, got tools/list"
}
