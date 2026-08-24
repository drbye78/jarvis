package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.ToolCall
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Wraps [MessageDao] to provide conversation persistence for the LLM layer.
 *
 * Keeps a 20-message cap (per blueprint): every inserted message triggers a
 * trim that retains only the most recent 20 rows. Decoupled from other phases
 * (no api/ or audio/ imports).
 */
class ConversationManager(private val dao: MessageDao) {

    private val json = Json { ignoreUnknownKeys = true }

    constructor(context: Context) : this(AppDatabase.getInstance(context).messageDao())

    suspend fun addMessage(role: String, content: String) {
        addMessage(Message(role = role, content = content))
    }

    suspend fun addMessage(message: Message) {
        dao.insert(message.toEntity())
        // Keep last MAX_MESSAGES + any orphaned tool results whose parent assistant is kept
        val all = dao.all()
        val assistantKeepIds = all.takeLast(MAX_MESSAGES).map { it.id }.toMutableSet()
        // Also keep tool messages whose parent assistant (by toolCallId) is in the keep set
        val assistantToolCallIds = all.filter { it.role == "assistant" && it.toolCallsJson != null }
            .filter { it.id in assistantKeepIds }
            .flatMap { entity ->
                entity.toolCallsJson?.let { jsonStr ->
                    try {
                        val toolCalls = json.decodeFromString(
                            ListSerializer(ToolCall.serializer()),
                            jsonStr
                        )
                        toolCalls.map { it.id }
                    } catch (e: Exception) { emptyList() }
                } ?: emptyList()
            }.toSet()
        val toolKeepIds = all.filter { it.role == "tool" && it.toolCallId in assistantToolCallIds }.map { it.id }
        assistantKeepIds.addAll(toolKeepIds)
        dao.trimToIds(assistantKeepIds)
    }

    /**
     * Returns the last [MAX_MESSAGES] messages mapped to [Message] in
     * chronological (oldest-first) order, suitable for sending to the LLM.
     */
    suspend fun getHistoryForLLM(): List<Message> {
        return dao.recentDesc(MAX_MESSAGES)
            .reversed()
            .map { it.toMessage() }
    }

    suspend fun getHistory(): List<Message> = getHistoryForLLM()

    suspend fun clear() {
        dao.clear()
    }

    private fun Message.toEntity() = MessageEntity(
        role = role,
        content = content,
        name = name,
        toolCallsJson = toolCalls?.let { json.encodeToString(ListSerializer(com.jarvis.assistant.contracts.ToolCall.serializer()), it) },
        toolCallId = toolCallId
    )

    private fun MessageEntity.toMessage() = Message(
        role = role,
        content = content,
        name = name,
        toolCalls = toolCallsJson?.let { json.decodeFromString(ListSerializer(com.jarvis.assistant.contracts.ToolCall.serializer()), it) },
        toolCallId = toolCallId
    )

    companion object {
        const val MAX_MESSAGES: Int = 20
    }
}