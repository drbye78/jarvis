package com.jarvis.assistant.contracts

import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall
)

@Serializable
data class Tool(
    val type: String = "function",
    val function: ToolFunction
)

@Serializable
data class ToolFunction(
    val name: String,
    val description: String,
    val parameters: String // JSON schema as a string
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String // raw JSON string
)

@Serializable
data class ToolResult(
    val call: FunctionCall,
    val result: String
)

/**
 * Streaming LLM output. Tool calls arrive as incremental [FunctionCallDelta]s:
 * [argsDelta] must be concatenated across chunks (keyed by [index]) and only
 * finalized into [FunctionCallComplete] when the stream ends ([Done]).
 */
sealed interface LlmChunk {
    data class Text(val text: String) : LlmChunk
    data class FunctionCallDelta(
        val index: Int,
        val name: String? = null,
        val argsDelta: String = ""
    ) : LlmChunk
    data class FunctionCallComplete(val call: ToolCall) : LlmChunk
    data object Done : LlmChunk
}

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

sealed interface AsrResult {
    data class Success(val text: String) : AsrResult
    data object NoSpeech : AsrResult
    data class Failure(val cause: Throwable) : AsrResult
}