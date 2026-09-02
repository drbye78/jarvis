package com.jarvis.assistant.wire

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI-compatible wire DTOs.
 *
 * Every field name that crosses the wire is pinned with [SerialName] to the
 * snake_case spelling the chat-completions protocol requires
 * (`tool_calls`, `tool_call_id`, `max_tokens`, ...). GigaChat implements the
 * same convention. The original bug — kotlinx.serialization emitting
 * `toolCalls` / `toolCallId` — made every follow-up request containing tool
 * messages schema-invalid (HTTP 400 or silently ignored tool results).
 */
@Serializable
data class WireChatRequest(
    val model: String? = null,
    val messages: List<WireMessage>,
    val tools: List<WireTool>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class WireMessage(
    val role: String,
    val content: String? = null,
    val name: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunctionCall,
)

@Serializable
data class WireFunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class WireTool(
    val type: String = "function",
    val function: WireToolFunction,
)

@Serializable
data class WireToolFunction(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

// ---------------------------------------------------------------------------
// Domain <-> wire mappers
// ---------------------------------------------------------------------------

fun Message.toWire(): WireMessage = WireMessage(
    role = role,
    // Audit #22: the chat-completions spec requires a NON-NULL content string
    // for user (and tool) messages; a blank utterance serialized as null made
    // the whole request fail HTTP 400. Blank user/tool content is upgraded to
    // a single space. Assistant messages legitimately carry null content when
    // they consist only of tool_calls.
    content = when (role) {
        "user", "tool" -> content.ifBlank { " " }
        else -> content.ifBlank { null }
    },
    name = name,
    toolCalls = toolCalls?.map { it.toWire() },
    toolCallId = toolCallId,
)

fun ToolCall.toWire(): WireToolCall = WireToolCall(
    id = id,
    type = type,
    function = WireFunctionCall(function.name, function.arguments),
)

fun WireToolCall.toDomain(): ToolCall = ToolCall(
    id = id,
    type = type,
    function = FunctionCall(function.name, function.arguments),
)

fun ToolDefinition.toWire(): WireTool = WireTool(
    function = WireToolFunction(name, description, parameters),
)

fun ChatRequest.toWire(): WireChatRequest = WireChatRequest(
    model = model,
    messages = messages.map { it.toWire() },
    tools = if (tools.isEmpty()) null else tools.map { it.toWire() },
    toolChoice = if (tools.isEmpty()) null else "auto",
    stream = true,
    temperature = temperature,
    maxTokens = maxTokens,
)
