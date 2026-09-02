package com.jarvis.assistant.llm

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.flow.Flow

/** Streaming chat-completions client contract. */
interface LlmClient {
    fun chatStream(request: ChatRequest): Flow<LlmChunk>
}
