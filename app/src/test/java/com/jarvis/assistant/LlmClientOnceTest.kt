package com.jarvis.assistant

import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.llm.withLlmRetry
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * COGNITIVE_PLAN 0.8: the Cognitive Core's background transport seam.
 * `chatOnce` must deliver one full completion from the streaming contract
 * without new transport code, and `withLlmRetry` must retry ONLY transient
 * failures (429/5xx/IOException), rethrow fatal ones immediately, and never
 * swallow CancellationException. Virtual time (runTest) proves the backoff
 * sleeps are real but bounded.
 */
class LlmClientOnceTest {

    private class ScriptedClient(private val chunks: List<LlmChunk>) : LlmClient {
        override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
            chunks.forEach { emit(it) }
        }
    }

    @Test
    fun `chatOnce concatenates text chunks and ignores Done`() = runTest {
        val client = ScriptedClient(
            listOf(
                LlmChunk.Text("{\"facts\":["),
                LlmChunk.Text("{\"subject\":\"user\"}]}"),
                LlmChunk.Done,
            ),
        )
        val text = client.chatOnce(
            ChatRequest(messages = emptyList(), tools = emptyList(), temperature = 0.0, maxTokens = 256),
        )
        assertEquals("{\"facts\":[{\"subject\":\"user\"}]}", text)
    }

    @Test
    fun `chatOnce tolerates a stream without Done (EOF tolerance)`() = runTest {
        val client = ScriptedClient(listOf(LlmChunk.Text("частичный ответ")))
        assertEquals("частичный ответ", client.chatOnce(ChatRequest(messages = emptyList(), tools = emptyList())))
    }

    // ------------------------------------------------------------------
    // withLlmRetry
    // ------------------------------------------------------------------

    @Test
    fun `retry returns on first success without sleeping`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()
        val result = withLlmRetry(attempts = 3, onRetry = { attempt, _, _ -> retries.add(attempt) }) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `transient 5xx is retried then succeeds`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()
        val result = withLlmRetry(attempts = 3, onRetry = { attempt, _, _ -> retries.add(attempt) }) {
            calls++
            if (calls < 3) throw LlmHttpException(500)
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, calls)
        assertEquals(listOf(1, 2), retries)
    }

    @Test
    // SwallowedException is suppressed deliberately: in a TEST, "handling"
    // the exception means asserting its payload — the rule targets production
    // code where the throwable's diagnostics would be lost.
    @Suppress("SwallowedException")
    fun `fatal 4xx is NOT retried`() = runTest {
        var calls = 0
        try {
            withLlmRetry(attempts = 3) {
                calls++
                throw LlmHttpException(401)
            }
            fail("401 must propagate immediately")
        } catch (e: LlmHttpException) {
            assertEquals("401 must propagate without a retry", 401, e.code)
        }
        assertEquals(1, calls)
    }

    @Test
    @Suppress("SwallowedException") // asserting the payload IS the handling (see above)
    fun `transient failures exhaust the attempt budget and rethrow`() = runTest {
        var calls = 0
        val retries = mutableListOf<Int>()
        try {
            withLlmRetry(attempts = 2, onRetry = { attempt, _, _ -> retries.add(attempt) }) {
                calls++
                throw LlmHttpException(429)
            }
            fail("429 exhaustion must rethrow")
        } catch (e: LlmHttpException) {
            assertEquals("429 must exhaust the budget and rethrow", 429, e.code)
        }
        assertEquals(2, calls)
        assertEquals(listOf(1), retries)
    }

    @Test
    fun `IOException is retried like a transient failure`() = runTest {
        var calls = 0
        val result = withLlmRetry(attempts = 2) {
            calls++
            if (calls == 1) throw IOException("connection reset")
            "after-drop"
        }
        assertEquals("after-drop", result)
        assertEquals(2, calls)
    }

    @Test
    fun `CancellationException is rethrown - never swallowed (A8 ban)`() = runTest {
        try {
            withLlmRetry(attempts = 3) {
                throw CancellationException("scope cancelled")
            }
            fail("cancellation must rethrow")
        } catch (expected: CancellationException) {
            assertEquals("scope cancelled", expected.message)
        }
    }

    @Test
    @Suppress("SwallowedException") // asserting the payload IS the handling (see above)
    fun `backoff grows geometrically and is capped`() = runTest {
        val delays = mutableListOf<Long>()
        var calls = 0
        try {
            withLlmRetry(
                attempts = 4,
                initialDelayMs = 100,
                maxDelayMs = 250,
                factor = 3.0,
                onRetry = { _, _, delayMs -> delays.add(delayMs) },
            ) {
                calls++
                throw LlmHttpException(500)
            }
            fail("must exhaust")
        } catch (e: LlmHttpException) {
            assertEquals("500 must exhaust the budget and rethrow", 500, e.code)
        }
        assertEquals(listOf(100L, 250L, 250L), delays) // 100 → 300 capped at 250
    }
}
