package com.jarvis.assistant.wire

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * GigaChat **native** (`/v2/chat/completions`) wire DTOs — LIVE-VERIFIED
 * 2026-09 against `api.giga.chat` (see `/tmp/opencode/GC_CONTRACT.md`).
 *
 * This is deliberately a SEPARATE schema from [WireChatRequest]: the native
 * contract differs on every axis (content is an array of parts, `tools`
 * carries `web_search` + `functions.specifications`, sampling lives under
 * `model_options`, the response envelope is `messages[]`, and the stream uses
 * named `event:` lines). Mixing the two would silently re-introduce the
 * legacy shapes the server rejects with HTTP 400/422.
 *
 * Request field order mirrors the verified example; it is not semantically
 * required but keeps captured bodies readable.
 */
@Serializable
data class GigaChatChatRequest(
    val model: String,
    val messages: List<GigaChatChatMessage>,
    val tools: List<GigaChatTool>? = null,
    @SerialName("tool_config") val toolConfig: GigaChatToolConfig? = null,
    @SerialName("model_options") val modelOptions: GigaChatModelOptions? = null,
    @SerialName("user_info") val userInfo: GigaChatUserInfo? = null,
    // No default: `encodeDefaults = false` would drop `"stream":true`, and the
    // server must be told to stream.
    val stream: Boolean,
)

/** One chat message; `content` MUST be an array of parts (a string → 400). */
@Serializable
data class GigaChatChatMessage(
    val role: String,
    val content: List<GigaChatPart>,
)

/**
 * A single content part. Exactly one of [text] / [functionCall] /
 * [functionResult] is populated; with `encodeDefaults = false` (the client's
 * JSON config) the `null` members are omitted from the wire.
 */
@Serializable
data class GigaChatPart(
    val text: String? = null,
    @SerialName("function_call") val functionCall: GigaChatFunctionCall? = null,
    @SerialName("function_result") val functionResult: GigaChatFunctionResult? = null,
)

/**
 * A client function call. On `/v2` [arguments] is a JSON **object** (the
 * legacy string form is rejected); [id] is only populated on responses.
 */
@Serializable
data class GigaChatFunctionCall(
    val id: String? = null,
    val name: String? = null,
    val arguments: JsonElement? = null,
)

/** A tool result: `result` is a JSON **string** per the verified contract. */
@Serializable
data class GigaChatFunctionResult(
    val name: String,
    val result: String,
)

/**
 * One entry of the `tools` array: either the built-in `{"web_search":{}}` or
 * the client-tool specification blob. The `[OI]` `{"type":"function",...}`
 * shape is rejected by the server.
 */
@Serializable
data class GigaChatTool(
    @SerialName("web_search") val webSearch: JsonObject? = null,
    val functions: GigaChatFunctions? = null,
)

@Serializable
data class GigaChatFunctions(
    val specifications: List<GigaChatFunctionSpec>,
)

@Serializable
data class GigaChatFunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class GigaChatToolConfig(
    // No default: a defaulted `"auto"` would be dropped by encodeDefaults=false.
    val mode: String,
)

@Serializable
data class GigaChatModelOptions(
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class GigaChatUserInfo(
    val timezone: String,
)

// ---------------------------------------------------------------------------
// Response (shared by the non-stream body and every stream event payload)
// ---------------------------------------------------------------------------

@Serializable
data class GigaChatChatResponse(
    val model: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val messages: List<GigaChatResponseMessage> = emptyList(),
    @SerialName("finish_reason") val finishReason: String? = null,
    val usage: GigaChatUsage? = null,
)

@Serializable
data class GigaChatResponseMessage(
    val role: String? = null,
    val content: List<GigaChatResponsePart> = emptyList(),
    @SerialName("tool_state_id") val toolStateId: String? = null,
)

@Serializable
data class GigaChatResponsePart(
    val text: String? = null,
    @SerialName("function_call") val functionCall: GigaChatFunctionCall? = null,
    @SerialName("inline_data") val inlineData: GigaChatInlineData? = null,
    @SerialName("tool_execution") val toolExecution: GigaChatToolExecution? = null,
)

@Serializable
data class GigaChatInlineData(
    val sources: Map<String, GigaChatSource>? = null,
    val images: List<JsonElement> = emptyList(),
)

@Serializable
data class GigaChatSource(
    val url: String? = null,
    val title: String? = null,
)

@Serializable
data class GigaChatToolExecution(
    val id: String? = null,
    val name: String? = null,
    val status: String? = null,
)

@Serializable
data class GigaChatUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

// ---------------------------------------------------------------------------
// Domain -> native request mapping
// ---------------------------------------------------------------------------

private val gigaChatWireJson = Json { ignoreUnknownKeys = true }

/**
 * Maps the provider-neutral domain [ChatRequest] onto the native body.
 *
 * - The 19 client [ToolDefinition]s are re-serialised under
 *   `functions.specifications` (the `[OI]` tool shape is a 400).
 * - When [webSearch] is true, `{"web_search":{}}` is merged into the same
 *   `tools` array and `tool_config.mode` is `"auto"` (the model decides when
 *   to search — `forced` is a verified 400).
 * - [model] / [timezone] are caller-owned: the client injects its configured
 *   default and the current zone, so this mapper stays pure.
 *
 * The system message is expected to be first: the session lane builds
 * `listOf(Message.system(...)) + history`, and order is preserved verbatim.
 */
fun ChatRequest.toGigaChatNative(
    model: String,
    timezone: String?,
    webSearch: Boolean = true,
): GigaChatChatRequest {
    val nativeTools = buildList {
        if (webSearch) add(GigaChatTool(webSearch = JsonObject(emptyMap())))
        if (tools.isNotEmpty()) {
            add(GigaChatTool(functions = GigaChatFunctions(tools.map { it.toGigaChatSpec() })))
        }
    }
    return GigaChatChatRequest(
        model = model,
        messages = messages.map { it.toGigaChatMessage() },
        tools = nativeTools.ifEmpty { null },
        // "auto" is required for web_search and harmless for pure client-tool
        // turns, so it rides whenever any tool is advertised.
        toolConfig = if (nativeTools.isEmpty()) null else GigaChatToolConfig(mode = "auto"),
        modelOptions = GigaChatModelOptions(temperature = temperature, maxTokens = maxTokens),
        userInfo = timezone?.takeIf { it.isNotBlank() }?.let { GigaChatUserInfo(it) },
        stream = true,
    )
}

fun ToolDefinition.toGigaChatSpec(): GigaChatFunctionSpec =
    GigaChatFunctionSpec(name = name, description = description, parameters = parameters)

fun Message.toGigaChatMessage(): GigaChatChatMessage {
    val parts = buildList {
        if (role == "tool") {
            // role:"tool" carries name + content; role:"function" is a 422.
            add(
                GigaChatPart(
                    functionResult = GigaChatFunctionResult(
                        name = name.orEmpty(),
                        result = content.ifBlank { " " },
                    )
                )
            )
        } else {
            if (content.isNotBlank()) add(GigaChatPart(text = content))
            toolCalls.orEmpty().forEach { tc ->
                add(
                    GigaChatPart(
                        functionCall = GigaChatFunctionCall(
                            name = tc.function.name,
                            arguments = toolArgumentsToJson(tc.function.arguments),
                        )
                    )
                )
            }
            // The native server requires a non-empty parts array; an assistant
            // message that is neither prose nor a call (rare) degrades to a
            // single space rather than an invalid `content: []`.
            if (isEmpty()) add(GigaChatPart(text = " "))
        }
    }
    return GigaChatChatMessage(role = role, content = parts)
}

/**
 * The domain stores tool arguments as a JSON string; `/v2` wants an object.
 * Blank or unparseable input (only reachable with a malformed provider echo)
 * degrades to `{}` rather than emitting an invalid scalar argument.
 */
private fun toolArgumentsToJson(raw: String): JsonElement {
    if (raw.isBlank()) return JsonObject(emptyMap())
    return runCatching { gigaChatWireJson.parseToJsonElement(raw) }
        // A bare string/number parsed from a corrupt echo is not a valid
        // arguments object; normalise it away.
        .getOrElse { JsonObject(emptyMap()) }
        .let { parsed -> if (parsed is JsonObject) parsed else JsonObject(emptyMap()) }
}
