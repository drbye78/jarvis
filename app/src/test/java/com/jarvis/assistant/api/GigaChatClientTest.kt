package com.jarvis.assistant.api

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.LlmChunk
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.TokenProvider
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GigaChatClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GigaChatClient

    private val fakeTokenProvider = object : TokenProvider {
        override suspend fun getGigaChatToken() = "test-token"
        override suspend fun getSaluteToken() = "test-token"
    }

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        val config = JarvisConfig(gigaChatEndpoint = server.url("/").toString())
        client = GigaChatClient(fakeTokenProvider, OkHttpClient.Builder().build(), config)
    }

    @After fun teardown() { server.shutdown() }

    @Test fun `text only response`() = runBlocking {
        server.enqueue(MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n"))
        val chunks = client.chatStream(listOf(Message("user", "test")), emptyList()).toList()
        assertTrue(chunks.any { it is LlmChunk.Text && it.text == "Hello" })
        assertTrue(chunks.any { it is LlmChunk.Done })
    }

    @Test fun `single tool call with incremental arguments`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"getWeather\",\"arguments\":\"{\\\"loc\"}}]}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"ation\\\":\\\"Moscow\\\"}\"}}]}}]}\n\n" +
            "data: {\"choices\":[{\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"
        ))
        val chunks = client.chatStream(listOf(Message("user", "test")), emptyList()).toList()
        val toolCalls = chunks.filterIsInstance<LlmChunk.FunctionCallComplete>()
        assertEquals(1, toolCalls.size)
        assertEquals("call_1", toolCalls[0].call.id)
        assertEquals("getWeather", toolCalls[0].call.function.name)
        assertTrue(toolCalls[0].call.function.arguments.contains("Moscow"))
    }

    @Test fun `empty lines and comments skipped`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            ": this is a comment\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}\n\n" +
            "\n" +
            "data: [DONE]\n\n"
        ))
        val chunks = client.chatStream(listOf(Message("user", "test")), emptyList()).toList()
        assertTrue(chunks.any { it is LlmChunk.Text && it.text == "Hi" })
    }

    @Test fun `blank lines skipped`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            "\n\n" +
            "data: {\"choices\":[{\"delta\":{\"content\":\"Test\"}}]}\n\n" +
            "data: [DONE]\n\n"
        ))
        val chunks = client.chatStream(listOf(Message("user", "test")), emptyList()).toList()
        assertEquals("Test", (chunks.filterIsInstance<LlmChunk.Text>().first()).text)
    }

    @Test fun `server error throws`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))
        try {
            client.chatStream(listOf(Message("user", "test")), emptyList()).toList()
            fail("Expected exception")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("500"))
        }
    }
}
