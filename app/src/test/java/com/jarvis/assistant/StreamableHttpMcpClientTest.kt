package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpCall2
import com.jarvis.assistant.mcp.McpEnvelope
import com.jarvis.assistant.mcp.McpResultParser
import com.jarvis.assistant.mcp.McpToolResult2
import com.jarvis.assistant.mcp.McpToolsCall
import com.jarvis.assistant.mcp.StreamableHttpMcpClient
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
 *
 * The real DeepWiki / Microsoft Learn payloads below also record the P1 fixes:
 *  (a) a server `Mcp-Session-Id` IS now captured and echoed back, while a
 *      server that never issues one is not treated as fatal;
 *  (b) every request carries `MCP-Protocol-Version: 2025-06-18`, and a
 *      successful `initialize` is followed by a `notifications/initialized`;
 *  (c) a JSON-RPC `error` object and a tool-level `isError` are DISTINCT: the
 *      former surfaces as [McpCall2.ProtocolError] with its code (and HTTP
 *      status), the latter as an [McpCall2.Ok] result flagged `isError`;
 *  (d) `tools/list` is paged (cursor + hard page cap) and a discovery reply is
 *      never mistaken for a successful empty tool result.
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

    /** Unwraps a successful call, failing loudly on a non-Ok classification. */
    private fun ok(call: McpCall2): McpToolResult2 {
        assertTrue("expected Ok but was $call", call is McpCall2.Ok)
        return (call as McpCall2.Ok).result
    }

    /** Unwraps a protocol error, failing loudly on any other classification. */
    private fun protocolError(call: McpCall2): McpCall2.ProtocolError {
        assertTrue("expected ProtocolError but was $call", call is McpCall2.ProtocolError)
        return call as McpCall2.ProtocolError
    }

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

        val result = ok(client().callTool("search_locations", """{"query":"Paris"}"""))

        assertEquals("""{"ok":true}""", result.text)
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

        val result = ok(client().callTool("search_locations", """{"query":"Paris"}"""))

        assertEquals("""{"ok":true}""", result.text)
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

        val result = ok(client().callTool("get_weather_forecast", "{}"))

        assertTrue(result.isError)
        assertTrue(result.text.contains("hours"))
    }

    @Test
    fun `a JSON-RPC level error stays distinct from a tool-level error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
            ),
        )

        val error = protocolError(client().callTool("nope", "{}"))

        // The code is preserved (NOT flattened into a boolean), and a 2xx body
        // means the server answered with HTTP 200.
        assertEquals(-32601, error.code)
        assertEquals("Method not found", error.message)
        assertEquals(200, error.httpStatus)
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
                """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-06-18"}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(202))
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{"ok":true}""")))

        val result = ok(client().callTool("search_locations", """{"query":"Paris"}"""))

        // The retry succeeded, and the FIRST request really preceded an initialize.
        assertEquals("""{"ok":true}""", result.text)
        val first = server.takeRequest()
        val second = server.takeRequest()
        val third = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("tools/call"))
        assertTrue(second.body.readUtf8().contains("initialize"))
        // Fix (b): a successful initialize is acknowledged.
        assertTrue(third.body.readUtf8().contains("notifications/initialized"))
    }

    @Test
    fun `an HTTP failure classifies as an answered-but-unusable reply`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = client().callTool("search_locations", "{}")

        // Reachable server, non-2xx with no JSON-RPC error: BadResponse.
        assertEquals(McpCall2.BadResponse, result)
    }

    @Test
    fun `an unreachable endpoint classifies as Unreachable`() = runBlocking {
        val url = server.url("/mcp/").toString()
        server.shutdown() // simulate the service being down

        val result = StreamableHttpMcpClient(OkHttpClient(), url).callTool("search_locations", "{}")

        assertEquals(McpCall2.Unreachable, result)
    }

    @Test
    fun `the request carries the tool name, a JSON argument object and the protocol version`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{}""")))

        client().callTool("get_weather_forecast", """{"latitude":1.5,"hours":24}""")

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains(""""name":"get_weather_forecast""""))
        // Arguments must be a JSON OBJECT, not a string-encoded blob.
        assertTrue(body.contains(""""arguments":{"latitude":1.5,"hours":24}"""))
        // Fix (b): the negotiated protocol version travels on the header.
        assertEquals("2025-06-18", request.getHeader("MCP-Protocol-Version"))
    }

    @Test
    fun `a configured auth pair is attached to every request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{}""")))
        val mcp = StreamableHttpMcpClient(
            httpClient = OkHttpClient(),
            endpointUrl = server.url("/mcp/").toString(),
            authHeaderName = "Authorization",
            authHeaderValue = "Bearer secret-token",
        )

        mcp.callTool("search_locations", "{}")

        assertEquals("Bearer secret-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `no auth header is sent when either half is blank`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(toolResultEnvelope("""{}""")))
        val mcp = StreamableHttpMcpClient(
            httpClient = OkHttpClient(),
            endpointUrl = server.url("/mcp/").toString(),
            authHeaderName = "Authorization",
            authHeaderValue = "   ",
        )

        mcp.callTool("search_locations", "{}")

        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    // --- Real-server payloads (captured byte-faithfully from live servers) ---

    @Test
    fun `parses a real SSE tool result with no structuredContent`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(deepWikiSuccessBody()),
        )

        val result = ok(client().callTool("ask_wiki_question", """{"repoName":"mcp"}"""))

        assertFalse(result.isError)
        assertTrue(result.text.contains("Available pages"))
    }

    @Test
    fun `a real unknown-tool reply is a tool error, not a protocol error`() = runBlocking {
        val body = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\"," +
            "\"text\":\"Unknown tool: no_such_tool_xyz\"}],\"isError\":true}}\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        val result = ok(client().callTool("no_such_tool_xyz", "{}"))

        assertTrue(result.isError)
        assertTrue(result.text.contains("Unknown tool"))
    }

    @Test
    fun `a real unsupported-version reply is a protocol error with its code and status`() = runBlocking {
        val body =
            "{\"jsonrpc\":\"2.0\",\"id\":\"server-error\",\"error\":{\"code\":-32600," +
                "\"message\":\"Bad Request: Unsupported protocol version: 2026-07-28. " +
                "Supported versions: 2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25\"}}"
        server.enqueue(MockResponse().setResponseCode(400).setBody(body))

        val error = protocolError(client().callTool("ask_wiki_question", """{"repoName":"mcp"}"""))

        // Fix (d): the 400 body is parsed, so -32600 is NOT indistinguishable
        // from an opaque HTTP 500, and the status is carried alongside the code.
        assertEquals(-32600, error.code)
        assertEquals(400, error.httpStatus)
        assertTrue(error.message.contains("Unsupported protocol version"))
    }

    @Test
    fun `parses a real SSE reply whose envelope omits jsonrpc and id, and echoes the session id`() = runBlocking {
        val body = "event: message\n" +
            "data: {\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]," +
            "\"isError\":false}}\n\n"
        val sessionId = "eyJjbGllbnRJbmZvIjp7Im5hbWUiOiJqYXJ2aXMtcHJvYmUifX0"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setHeader("Mcp-Session-Id", sessionId)
                .setBody(body),
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        val mcp = client()
        val first = ok(mcp.callTool("ask_wiki_question", """{"repoName":"mcp"}"""))
        ok(mcp.callTool("ask_wiki_question", """{"repoName":"mcp"}"""))

        val request = server.takeRequest()
        val followUp = server.takeRequest()
        assertTrue(request.getHeader("Accept").orEmpty().contains("text/event-stream"))
        // Fix (a): the first request had no session yet …
        assertNull(request.getHeader("Mcp-Session-Id"))
        // … and the follow-up echoes the id the server advertised.
        assertEquals(sessionId, followUp.getHeader("Mcp-Session-Id"))
        assertEquals("ok", first.text)
        assertFalse(first.isError)
    }

    @Test
    fun `a real tools-list envelope is not a tool result`() = runBlocking {
        val body = "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"ask_wiki_question\"," +
            "\"description\":\"q\",\"inputSchema\":{\"type\":\"object\",\"properties\":" +
            "{\"repoName\":{\"type\":\"string\"}},\"required\":[\"repoName\"]}}]}}\n\n"
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body),
        )

        val result = ok(client().callTool("ask_wiki_question", """{"repoName":"mcp"}"""))

        // Fix (d): a discovery reply is flagged as a malformed tool result, NOT
        // reported as a successful empty tool result.
        assertTrue(result.isError)
        assertTrue(result.text.contains("tools/list"))
    }

    @Test
    fun `tools list follows the cursor, accumulates descriptors and hard-caps pages`() = runBlocking {
        server.enqueue(toolsResponse("alpha", cursor = "c1"))
        server.enqueue(toolsResponse("beta", cursor = null))

        val call = client().listTools()

        assertTrue("expected Ok but was $call", call is McpToolsCall.Ok)
        val page = (call as McpToolsCall.Ok).page
        assertEquals(listOf("alpha", "beta"), page.tools.map { it.name })
        assertNull(page.nextCursor)
        assertEquals("""{"type":"object"}""", page.tools.first().inputSchema)
        // The second page request carries the cursor from the first.
        server.takeRequest()
        val second = server.takeRequest().body.readUtf8()
        assertTrue(second.contains(""""cursor":"c1""""))
    }

    @Test
    fun `the pure parser extracts a protocol error from an SSE frame`() {
        val envelope = McpResultParser.parseEnvelope(
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32600," +
                "\"message\":\"Unsupported\"}}\n\n",
            "text/event-stream",
        )

        assertTrue("expected ProtocolError but was $envelope", envelope is McpEnvelope.ProtocolError)
        assertEquals(-32600, (envelope as McpEnvelope.ProtocolError).code)
        assertEquals("Unsupported", envelope.message)
    }

    @Test
    fun `a real SSE reply with a leading event line and trailing blank lines is parsed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(deepWikiSuccessBody()),
        )

        val result = ok(client().callTool("ask_wiki_question", """{"repoName":"mcp"}"""))

        assertTrue(result.text.contains("Available pages"))
        assertTrue(result.text.contains("- 1 Overview"))
    }

    /** One `tools/list` page, delivered as an SSE frame. */
    private fun toolsResponse(name: String, cursor: String?): MockResponse {
        val envelope = buildJsonObject {
            put("jsonrpc", JsonPrimitive("2.0"))
            put("id", JsonPrimitive(2))
            put(
                "result",
                buildJsonObject {
                    put(
                        "tools",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(name))
                                    put("description", JsonPrimitive("d"))
                                    put("inputSchema", buildJsonObject { put("type", JsonPrimitive("object")) })
                                },
                            )
                        },
                    )
                    cursor?.let { put("nextCursor", JsonPrimitive(it)) }
                },
            )
        }
        return MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody("event: message\ndata: $envelope\n\n")
    }

    /** DeepWiki `tools/call` success frame: `event:` line, trailing blank line, JSON `\n` literal. */
    private fun deepWikiSuccessBody(): String =
        "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\"," +
            "\"text\":\"Available pages for modelcontextprotocol/modelcontextprotocol:" +
            "\\n\\n- 1 Overview\\n- 2 Protocol Specification\"}]}}\n\n"
}
