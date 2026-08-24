package com.jarvis.assistant.data

import android.content.Context
import com.jarvis.assistant.contracts.Message

/**
 * Wraps [MessageDao] to provide conversation persistence for the LLM layer.
 *
 * Keeps a 20-message cap (per blueprint): every inserted message triggers a
 * trim that retains only the most recent 20 rows. Decoupled from other phases
 * (no api/ or audio/ imports).
 */
class ConversationManager(private val dao: MessageDao) {

    constructor(context: Context) : this(AppDatabase.getInstance(context).messageDao())

    suspend fun addMessage(role: String, content: String) {
        dao.insert(MessageEntity(role = role, content = content))
        dao.trimTo(MAX_MESSAGES)
    }

    /**
     * Returns the last [MAX_MESSAGES] messages mapped to [Message] in
     * chronological (oldest-first) order, suitable for sending to the LLM.
     */
    suspend fun getHistoryForLLM(): List<Message> {
        return dao.recentDesc(MAX_MESSAGES)
            .reversed()
            .map { Message(role = it.role, content = it.content) }
    }

    suspend fun clear() {
        dao.clear()
    }

    companion object {
        const val MAX_MESSAGES: Int = 20
    }
}
