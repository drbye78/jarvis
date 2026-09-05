package com.jarvis.assistant.llm

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import java.io.IOException

/** Streaming chat-completions client contract. */
interface LlmClient {
    fun chatStream(request: ChatRequest): Flow<LlmChunk>

    /**
     * COGNITIVE_PLAN 0.8: non-streaming convenience for the Cognitive Core's
     * background callers (fact extraction, summarization) — they need the
     * full completion, not incremental chunks, and must never race the turn
     * lane's SSE transport. Default implementation rides on [chatStream] by
     * concatenating the text chunks (no new transport code, fakes keep
     * working); a [LlmChunk.Done] terminator is expected but not required.
     * Tool-call chunks are ignored: extraction/summarization requests carry
     * no tool definitions.
     */
    suspend fun chatOnce(request: ChatRequest): String {
        val sb = StringBuilder()
        chatStream(request).collect { chunk ->
            if (chunk is LlmChunk.Text) sb.append(chunk.text)
        }
        return sb.toString()
    }
}

/**
 * COGNITIVE_PLAN 0.8: bounded retry for TRANSIENT LLM failures (429/5xx per
 * [LlmHttpException.isTransient], plus [IOException] transport drops) with
 * exponential backoff. Built for the Cognitive Core's queue workers: an
 * offline/overloaded endpoint must degrade to "queued, try later", never to
 * a lost or faked result.
 *
 * - Fatal failures (any other [LlmHttpException] = 4xx, parsing errors, …)
 *   rethrow immediately — retrying a bad request is pointless.
 * - [CancellationException] always rethrows (the A8 class of bug is banned).
 * - [attempts] is the TOTAL number of tries (1 = no retry).
 * - [onRetry] observes each retry (retryNumber starting at 1 for the first
 *   retry, the error that caused it, and the backoff actually slept).
 */
suspend fun <T> withLlmRetry(
    attempts: Int = 3,
    initialDelayMs: Long = 2_000,
    maxDelayMs: Long = 30_000,
    factor: Double = 3.0,
    onRetry: (attempt: Int, error: Throwable, delayMs: Long) -> Unit = { _, _, _ -> },
    block: suspend (attempt: Int) -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1" }
    var backoffMs = initialDelayMs
    repeat(attempts) { attempt ->
        try {
            return block(attempt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmHttpException) {
            if (!e.isTransient || attempt == attempts - 1) throw e
            onRetry(attempt + 1, e, backoffMs)
            delay(backoffMs)
            backoffMs = (backoffMs * factor).toLong().coerceAtMost(maxDelayMs)
        } catch (e: IOException) {
            if (attempt == attempts - 1) throw e
            onRetry(attempt + 1, e, backoffMs)
            delay(backoffMs)
            backoffMs = (backoffMs * factor).toLong().coerceAtMost(maxDelayMs)
        }
    }
    // Unreachable: the last attempt either returns or rethrows.
    throw IllegalStateException("withLlmRetry exhausted attempts without returning")
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
