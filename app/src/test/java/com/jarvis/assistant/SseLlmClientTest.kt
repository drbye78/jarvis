package com.jarvis.assistant

import com.jarvis.assistant.llm.OpenAiCompatClient
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ChatRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pins the SSE body lifecycle (M4): the response body is closed exactly once
 * on every turn exit — normal [DONE] and EOF alike — with no pooled-connection
 * leak per turn.
 */
class SseLlmClientTest {

    /** Counts how many response bodies the client actually closed. */
    private class CloseCountingInterceptor : Interceptor {
        val closedCount = AtomicInteger()

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val original = response.body ?: return response
            val counted = object : ForwardingSource(original.source()) {}
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = original.contentType()
                override fun contentLength() = original.contentLength()
                override fun source() = counted.buffer()
                override fun close() {
                    closedCount.incrementAndGet()
                    counted.close()
                }
            }).build()
        }
    }

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun clientWith(counter: CloseCountingInterceptor): OpenAiCompatClient =
        OpenAiCompatClient(
            httpClient = OkHttpClient.Builder().addInterceptor(counter).build(),
            baseUrl = server.url("/v1").toString(),
            apiKey = "test-key",
            defaultModel = "test-model",
        )

    private fun request() = ChatRequest(
        messages = listOf(Message.user("hi")),
        tools = emptyList(),
    )

    @Test
    fun `DONE turn closes response body exactly once`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        )
        val counter = CloseCountingInterceptor()
        val llm = clientWith(counter)

        val chunks = llm.chatStream(request()).toList()

        assertTrue(chunks.last() is LlmChunk.Done)
        assertEquals(listOf("Hi"), chunks.filterIsInstance<LlmChunk.Text>().map { it.text })
        assertEquals("body not closed on [DONE] exit", 1, counter.closedCount.get())
    }

    @Test
    fun `EOF turn closes response body exactly once`() = runBlocking {
        // No [DONE] sentinel: the stream just ends (server closes).
        server.enqueue(
            MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"stop\"}]}\n\n"
            )
        )
        val counter = CloseCountingInterceptor()
        val llm = clientWith(counter)

        val chunks = llm.chatStream(request()).toList()

        assertTrue(chunks.last() is LlmChunk.Done) // EOF still finalizes with Done
        assertEquals("body not closed on EOF exit", 1, counter.closedCount.get())
    }

    @Test
    fun `http error turn closes response body`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val counter = CloseCountingInterceptor()
        val llm = clientWith(counter)

        val error = runCatching { llm.chatStream(request()).toList() }.exceptionOrNull()

        assertTrue(error != null)
        assertTrue(error!!.message!!.contains("HTTP 500"))
        assertEquals("body not closed on error exit", 1, counter.closedCount.get())
    }
}
