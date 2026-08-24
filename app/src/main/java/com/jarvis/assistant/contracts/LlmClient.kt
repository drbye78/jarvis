package com.jarvis.assistant.contracts

import kotlinx.coroutines.flow.Flow

interface LlmClient {
    fun chatStream(
        messages: List<Message>,
        tools: List<Tool>
    ): Flow<LlmChunk>
}