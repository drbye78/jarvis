package com.jarvis.assistant.llm

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.flow.Flow

/** Streaming chat-completions client contract. */
interface LlmClient {
    fun chatStream(request: ChatRequest): Flow<LlmChunk>
}

/**
 * Non-2xx from a chat-completions endpoint. TYPED (the old bare
 * RuntimeException could only be classified by parsing its message): callers
 * can now distinguish fatal 4xx (bad auth / bad request — retrying is
 * pointless) from transient 5xx (upstream overload — worth one retry).
 */
class LlmHttpException(val code: Int) : RuntimeException("LLM request failed (HTTP $code)") {
    /** 5xx and 429: the endpoint is alive but struggling — retryable. */
    val isTransient: Boolean get() = code >= 500 || code == 429
}
