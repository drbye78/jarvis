package com.jarvis.assistant.model

/**
 * Domain models for the conversation pipeline.
 *
 * IMPORTANT: these are pure domain types with NO serialization annotations.
 * Everything that crosses the network boundary goes through the `wire` package,
 * where OpenAI-compatible snake_case names are enforced with @SerialName.
 * This separation is what fixes the original wire-format defect (toolCalls /
 * toolCallId being sent camelCase to GigaChat, breaking every second request).
 */

data class Message(
    val role: String,
    val content: String,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
) {
    companion object {
        fun user(text: String) = Message(role = "user", content = text)
        fun assistant(text: String) = Message(role = "assistant", content = text)
        fun system(text: String) = Message(role = "system", content = text)
    }
}

data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

data class FunctionCall(
    val name: String,
    val arguments: String,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON schema for the arguments, as a parsed JSON object. */
    val parameters: kotlinx.serialization.json.JsonObject,
)

data class ChatRequest(
    val messages: List<Message>,
    val tools: List<ToolDefinition>,
    val model: String? = null,
    val temperature: Double = 0.7,
    val maxTokens: Int = 2048,
)

/**
 * Streaming LLM output. Tool calls arrive as incremental [FunctionCallDelta]s:
 * [argsDelta] must be concatenated across chunks (keyed by [index]) and only
 * finalized into [FunctionCallComplete] when the stream ends or a
 * finish_reason is observed.
 */
sealed interface LlmChunk {
    data class Text(val text: String) : LlmChunk
    data class FunctionCallDelta(
        val index: Int,
        val name: String? = null,
        val argsDelta: String = "",
    ) : LlmChunk

    data class FunctionCallComplete(val call: ToolCall) : LlmChunk
    data object Done : LlmChunk
}

enum class AssistantState { IDLE, LISTENING, THINKING, SPEAKING }

/** Result of a completed ASR utterance. */
sealed interface AsrOutcome {
    /** User said something; [text] is the final transcript. */
    data class Final(val text: String) : AsrOutcome

    /** Nothing intelligible was said. */
    data object NoSpeech : AsrOutcome

    /** The ASR stream failed after retries. */
    data class Failed(val cause: Throwable) : AsrOutcome
}
