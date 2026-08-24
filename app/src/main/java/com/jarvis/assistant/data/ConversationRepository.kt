package com.jarvis.assistant.data

import com.jarvis.assistant.contracts.Message

interface ConversationRepository {
    suspend fun addMessage(message: Message)
    suspend fun getHistory(): List<Message>
    suspend fun clear()
}