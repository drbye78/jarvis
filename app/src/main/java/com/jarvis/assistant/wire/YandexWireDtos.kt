package com.jarvis.assistant.wire

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Yandex AI Studio **Responses API** wire DTOs — LIVE-VERIFIED 2026-09 against
 * `https://ai.api.cloud.yandex.net/v1` (see `/tmp/opencode/YANDEX_CONTRACT.md`).
 *
 * This is a separate schema from both [WireChatRequest] and the GigaChat native
 * DTOs. The load-bearing differences:
 * - `input` is an ARRAY of items: `role`/`content` messages, plus flat
 *   `function_call` / `function_call_output` items paired by `call_id`;
 * - the system prompt goes in `instructions`, NOT an input item;
 * - tool schemas are **FLATTENED** (`{"type":"function","name",...}`) — the
 *   [OI]-style `{"type":"function","function":{...}}` nesting is wrong here;
 * - the built-in server-executed search is `{"type":"web_search"}`;
 * - the response envelope keys off `output[]` / `output_text`, and the stream
 *   dispatches on the JSON `data.type` (there is NO `[DONE]` sentinel).
 *
 * `encodeDefaults = false` (the client's JSON) omits every null member, so one
 * DTO can express each of the mutually-exclusive item shapes.
 */
@Serializable
data class YandexResponsesRequest(
    val model: String,
    val instructions: String? = null,
    val input: List<YandexInputItem>,
    // No default: `encodeDefaults = false` would drop `"stream":true`.
    val stream: Boolean,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null,
    val tools: List<YandexTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

/**
 * One `input` item. Exactly one of the three shapes is populated:
 * - message: [role] + [content]
 * - client call: [type] = `function_call` + [callId] + [name] + [arguments]
 * - tool result: [type] = `function_call_output` + [callId] + [output]
 */
@Serializable
data class YandexInputItem(
    val role: String? = null,
    val content: String? = null,
    val type: String? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val output: String? = null,
)

/**
 * One `tools` entry: the built-in `{"type":"web_search"}` or a FLATTENED
 * function tool (`parameters` inline, not nested under a `function` object).
 */
@Serializable
data class YandexTool(
    val type: String,
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonObject? = null,
)

// ---------------------------------------------------------------------------
// Response DTOs (non-stream body + `response.completed` / `response.failed`)
// ---------------------------------------------------------------------------

@Serializable
data class YandexResponse(
    val id: String? = null,
    val model: String? = null,
    val status: String? = null,
    val output: List<YandexOutputItem> = emptyList(),
    /** Convenience field the service adds for simple text turns. */
    @SerialName("output_text") val outputText: String? = null,
    val error: YandexError? = null,
)

@Serializable
data class YandexOutputItem(
    val type: String? = null,
    val id: String? = null,
    val role: String? = null,
    val status: String? = null,
    val content: List<YandexContentPart> = emptyList(),
    val name: String? = null,
    @SerialName("call_id") val callId: String? = null,
    /** A JSON STRING on the wire (e.g. `{"city":"Казань"}`). */
    val arguments: String? = null,
    val output: String? = null,
)

@Serializable
data class YandexContentPart(
    val type: String? = null,
    val text: String? = null,
)

/**
 * RFC-7807 problem detail (`{type,title,status,detail,instance}`) reused for
 * both HTTP error bodies and in-stream `response.failed` errors; [code] and
 * [message] are the in-stream variant's field names.
 */
@Serializable
data class YandexError(
    val type: String? = null,
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
    val message: String? = null,
)

/**
 * One decoded SSE frame. The stream carries named `event:` lines that merely
 * duplicate [type]; the parser dispatches on [type] and ignores the name.
 */
@Serializable
data class YandexStreamEvent(
    val type: String,
    val delta: String? = null,
    val item: YandexOutputItem? = null,
    val response: YandexResponse? = null,
    @SerialName("sequence_number") val sequenceNumber: Int? = null,
)

// ---------------------------------------------------------------------------
// Domain -> Responses API mapping
// ---------------------------------------------------------------------------

private const val SYSTEM_ROLE = "system"
private const val TOOL_ROLE = "tool"
private const val TYPE_FUNCTION = "function"
private const val TYPE_FUNCTION_CALL = "function_call"
private const val TYPE_FUNCTION_CALL_OUTPUT = "function_call_output"
private const val TYPE_WEB_SEARCH = "web_search"
private const val AUTO = "auto"

/**
 * Maps the provider-neutral domain [ChatRequest] onto the Responses body.
 *
 * - System messages become `instructions` (newline-joined); the contract's
 *   canonical system slot is not an `input` item.
 * - History maps per [Message.toYandexInputItems]: user/assistant → role+content,
 *   an assistant tool call → `function_call`, a tool result →
 *   `function_call_output` (paired by the preserved `call_id`).
 * - When [webSearchEnabled], `{"type":"web_search"}` is prepended to the
 *   flattened function [tools], and `tool_choice` is `"auto"` (the model
 *   decides when to search).
 *
 * [modelUri] is the full `gpt://<folder>/<model>/latest` URI: the client owns
 * folder resolution, so this mapper stays pure.
 */
fun ChatRequest.toYandexResponses(
    modelUri: String,
    webSearchEnabled: Boolean,
): YandexResponsesRequest {
    val wireTools = buildList {
        if (webSearchEnabled) add(YandexTool(type = TYPE_WEB_SEARCH))
        this@toYandexResponses.tools.forEach { add(it.toYandexTool()) }
    }
    return YandexResponsesRequest(
        model = modelUri,
        instructions = systemInstructions(),
        input = messages.filter { it.role != SYSTEM_ROLE }.flatMap { it.toYandexInputItems() },
        stream = true,
        maxOutputTokens = maxTokens,
        temperature = temperature,
        tools = wireTools.ifEmpty { null },
        toolChoice = if (wireTools.isEmpty()) null else AUTO,
    )
}

fun ToolDefinition.toYandexTool(): YandexTool =
    YandexTool(type = TYPE_FUNCTION, name = name, description = description, parameters = parameters)

/**
 * Maps one domain message to its `input` item(s). An assistant turn may carry
 * BOTH prose and client calls (the native providers allow it), so it expands to
 * several items; system messages are filtered out before this by
 * [toYandexResponses].
 */
fun Message.toYandexInputItems(): List<YandexInputItem> {
    if (role == TOOL_ROLE) {
        return listOf(
            YandexInputItem(
                type = TYPE_FUNCTION_CALL_OUTPUT,
                callId = toolCallId,
                output = content.ifBlank { " " },
            )
        )
    }
    return buildList {
        if (content.isNotBlank()) add(YandexInputItem(role = role, content = content))
        toolCalls.orEmpty().forEach { call ->
            add(
                YandexInputItem(
                    type = TYPE_FUNCTION_CALL,
                    callId = call.id,
                    name = call.function.name,
                    arguments = call.function.arguments,
                )
            )
        }
    }
}

/** Newline-joined system prompt, or null when there is no system message. */
private fun ChatRequest.systemInstructions(): String? = messages
    .asSequence()
    .filter { it.role == SYSTEM_ROLE }
    .map { it.content }
    .filter { it.isNotBlank() }
    .joinToString("\n")
    .takeIf { it.isNotBlank() }
