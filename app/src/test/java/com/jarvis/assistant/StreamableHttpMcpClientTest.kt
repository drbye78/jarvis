package com.jarvis.assistant

import com.jarvis.assistant.weather.StreamableHttpMcpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MCP Streamable-HTTP transport. Two protocol details decide whether the
 * weather lane works at all and are pinned here: the reply may be plain JSON
 * OR an SSE stream, and a server that demands `initialize` must not fail the
 * turn. Every request goes to MockWebServer — no unit test reaches the
 * internet.
 */
class StreamableHttpMcpClientTest {

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

    private fun client() = StreamableHttpMcpClient(
        httpClient = OkHttpClient(),
        endpointUrl = server.url("/mcp/").toString(),
    )

    private fun toolResultEnvelope(structured: String, isError: Boolean = false): String =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(1))
            put(
                "result",
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    // The text block carries the SAME JSON as a
                                    // STRING — it must be properly encoded.
                                    put("text", JsonPrimitive(structured))
                                },
                            )
                        },
                    )
                    put("structuredContent", Json.parseToJsonElement(structured))
                    put("isError", JsonPrimitive(isError))
                },
            )
        }.toString()

    @Test
    fun `parses a plain JSON tool result`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{"ok":true}""")))

        val result = client().callTool("search_locations", """{"query":"Paris"}""")

        assertEquals("""{"ok":true}""", result!!.text)
        assertFalse(result.isError)
    }

    @Test
    fun `parses a tool result delivered as an SSE stream`() = runBlocking {
        // MCP servers may answer with text/event-stream; the terminal data
        // frame carries the JSON-RPC envelope.
        val body = "event: message\ndata: ${toolResultEnvelope("""{"ok":true}""")}\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        val result = client().callTool("search_locations", """{"query":"Paris"}""")

        assertEquals("""{"ok":true}""", result!!.text)
        assertFalse(result.isError)
    }

    @Test
    fun `propagates a tool-level isError`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"bad"}],""" +
                    """"structuredContent":{"error":"hours must be 1 to 168"},"isError":true}}""",
            ),
        )

        val result = client().callTool("get_weather_forecast", "{}")

        assertTrue(result!!.isError)
        assertTrue(result.text.contains("hours"))
    }

    @Test
    fun `propagates a JSON-RPC level error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
            ),
        )

        val result = client().callTool("nope", "{}")

        assertTrue(result!!.isError)
        assertTrue(result.text.contains("Method not found"))
    }

    @Test
    fun `an un-initialized session retries once after initialize`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"session not initialized"}}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":0,"result":{"protocolVersion":"2024-11-05"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{"ok":true}""")))

        val result = client().callTool("search_locations", """{"query":"Paris"}""")

        // The retry succeeded, and the FIRST request really preceded an initialize.
        assertEquals("""{"ok":true}""", result!!.text)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("tools/call"))
        assertTrue(second.body.readUtf8().contains("initialize"))
    }

    @Test
    fun `an HTTP failure degrades to null rather than throwing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = client().callTool("search_locations", "{}")

        assertNull(result)
    }

    @Test
    fun `an unreachable endpoint degrades to null`() = runBlocking {
        val url = server.url("/mcp/").toString()
        server.shutdown() // simulate the service being down

        val result = StreamableHttpMcpClient(OkHttpClient(), url).callTool("search_locations", "{}")

        assertNull(result)
    }

    @Test
    fun `the request carries the tool name and a JSON argument object`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{}""")))

        client().callTool("get_weather_forecast", """{"latitude":1.5,"hours":24}""")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""name":"get_weather_forecast""""))
        // Arguments must be a JSON OBJECT, not a string-encoded blob.
        assertTrue(body.contains(""""arguments":{"latitude":1.5,"hours":24}"""))
    }
}
