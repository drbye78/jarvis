package com.jarvis.assistant.data

import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.wire.WireToolCall
import com.jarvis.assistant.wire.toDomain
import com.jarvis.assistant.wire.toWire
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Conversation persistence for the LLM layer.
 *
 * Defects fixed here:
 * 1. Ordering is by row id (monotonic), not createdAt (ambiguous ties).
 * 2. Assistant `tool_calls` and their tool results are inserted as ONE
 *    transaction ([addAssistantWithToolResults]) — an interruption can never
 *    leave a dangling half-pair in the table.
 * 3. [getHistoryForLLM] sanitizes POSITION-INDEPENDENTLY: a tool row whose
 *    assistant is not in the window is dropped wherever it sits, and an
 *    assistant whose ids have no tool results keeps its content with
 *    `tool_calls` cleared. Either half alone would make the chat-completions
 *    request schema-invalid (HTTP 400).
 */
class ConversationManager(
    private val dao: MessageDao,
    private val maxMessages: Int = 20,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun addMessage(role: String, content: String) {
        addMessage(Message(role = role, content = content))
    }

    suspend fun addMessage(message: Message) {
        dao.insert(message.toEntity())
        trim()
    }

    /** Persists the assistant message and its tool results atomically (C2). */
    suspend fun addAssistantWithToolResults(assistant: Message, results: List<Message>) {
        dao.insertAssistantWithResults(assistant.toEntity(), results.map { it.toEntity() })
        trim()
    }

    /** Keeps the newest [maxMessages] messages. Tool results always have higher
     *  ids than their assistant, so a simple id-based cutoff preserves pairs. */
    private suspend fun trim() {
        dao.deleteAllExceptRecent(maxMessages)
    }

    /**
     * History for the LLM: last [maxMessages] messages, oldest first, with
     * broken tool pairs removed position-independently (see class doc).
     */
    suspend fun getHistoryForLLM(): List<Message> {
        val window = dao.recentDesc(maxMessages)
            .reversed()
            .map { it.toMessage() }

        val assistantToolCallIds = window
            .filter { it.role == "assistant" }
            .flatMapTo(mutableSetOf()) { msg -> msg.toolCalls.orEmpty().map { call -> call.id } }
        val resultIds = window
            .filter { it.role == "tool" && it.toolCallId != null }
            .mapTo(mutableSetOf()) { it.toolCallId!! }

        return window.mapNotNull { msg ->
            when {
                // A tool result without its assistant in the window is dropped.
                msg.role == "tool" ->
                    msg.takeIf { it.toolCallId != null && it.toolCallId in assistantToolCallIds }

                // An assistant keeps only ids that actually have results; if
                // none remain the row survives with content but no tool_calls.
                msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty() -> {
                    val paired = msg.toolCalls!!.filter { it.id in resultIds }
                    when (paired.size) {
                        msg.toolCalls!!.size -> msg
                        0 -> msg.copy(toolCalls = null)
                        else -> msg.copy(toolCalls = paired)
                    }
                }

                else -> msg
            }
        }
    }

    /** Live transcript for the UI, newest last. */
    fun transcriptLive(limit: Int = 100): Flow<List<Message>> =
        dao.recentDescLive(limit).map { list -> list.reversed().map { it.toMessage() } }

    suspend fun clear() {
        dao.clear()
    }

    private fun Message.toEntity() = MessageEntity(
        role = role,
        content = content,
        name = name,
        toolCallsJson = toolCalls?.let { calls ->
            json.encodeToString(ListSerializer(WireToolCall.serializer()), calls.map { it.toWire() })
        },
        toolCallId = toolCallId,
    )

    private fun MessageEntity.toMessage() = Message(
        role = role,
        content = content,
        name = name,
        toolCalls = toolCallsJson?.let {
            runCatching {
                json.decodeFromString(ListSerializer(WireToolCall.serializer()), it)
                    .map { wire -> wire.toDomain() }
            }.getOrNull()
        },
        toolCallId = toolCallId,
    )
}
