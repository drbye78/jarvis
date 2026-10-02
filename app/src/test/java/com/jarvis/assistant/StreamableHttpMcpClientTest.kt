package com.jarvis.assistant

import com.jarvis.assistant.weather.McpCall
import com.jarvis.assistant.weather.McpToolResult
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
 *
 * Real DeepWiki / Microsoft Learn payloads (appended below) also record three
 * verified gaps for P1. These are factual characterizations of CURRENT
 * behavior, test-only — production is unchanged:
 *  (a) a server `Mcp-Session-Id` is never captured and never echoed back;
 *  (b) no `MCP-Protocol-Version` header and no `notifications/initialized` are sent;
 *  (c) a JSON-RPC `error` object and a tool-level `isError` are both flattened
 *      to `isError=true`, so a protocol failure and a tool failure look alike.
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
    private fun ok(call: McpCall): McpToolResult {
        assertTrue("expected Ok but was $call", call is McpCall.Ok)
        return (call as McpCall.Ok).result
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
    fun `propagates a JSON-RPC level error`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
            ),
        )

        val result = ok(client().callTool("nope", "{}"))

        assertTrue(result.isError)
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

        val result = ok(client().callTool("search_locations", """{"query":"Paris"}"""))

        // The retry succeeded, and the FIRST request really preceded an initialize.
        assertEquals("""{"ok":true}""", result.text)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertTrue(first.body.readUtf8().contains("tools/call"))
        assertTrue(second.body.readUtf8().contains("initialize"))
    }

    @Test
    fun `an HTTP failure classifies as an answered-but-unusable reply`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = client().callTool("search_locations", "{}")

        // Reachable server, non-2xx: BadResponse (NOT a failover trigger).
        assertEquals(McpCall.BadResponse, result)
    }

    @Test
    fun `an unreachable endpoint classifies as Unreachable`() = runBlocking {
        val url = server.url("/mcp/").toString()
        server.shutdown() // simulate the service being down

        val result = StreamableHttpMcpClient(OkHttpClient(), url).callTool("search_locations", "{}")

        assertEquals(McpCall.Unreachable, result)
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
    fun `a real unsupported-version reply is a protocol error`() = runBlocking {
        val body =
            "{\"jsonrpc\":\"2.0\",\"id\":\"server-error\",\"error\":{\"code\":-32600," +
                "\"message\":\"Bad Request: Unsupported protocol version: 2026-07-28. " +
                "Supported versions: 2024-11-05, 2025-03-26, 2025-06-18, 2025-11-25\"}}"
        server.enqueue(MockResponse().setResponseCode(400).setBody(body))

        val result = client().callTool("ask_wiki_question", """{"repoName":"mcp"}""")

        // CURRENT behavior: any non-2xx reply is BadResponse; the body is never
        // parsed, so a JSON-RPC protocol error is indistinguishable from HTTP 500.
        assertEquals(McpCall.BadResponse, result)
    }

    @Test
    fun `parses a real SSE reply whose envelope omits jsonrpc and id`() = runBlocking {
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
        // GAP (a): the client holds no session state, so even after the server
        // advertised `Mcp-Session-Id`, the follow-up request does not echo it.
        assertNull(followUp.getHeader("Mcp-Session-Id"))
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

        // KNOWN GAP: `toResult` has no `tools/list` path. With no `content` and
        // no `structuredContent` it hits the empty fallback and reports
        // isError=false — a discovery reply masquerades as a SUCCESSFUL empty
        // tool result. (The "Malformed MCP reply" branch needs `result` ABSENT,
        // which this envelope is not; discovery needs a separate path.)
        assertFalse(result.isError)
        assertEquals("Empty MCP reply", result.text)
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

    /** DeepWiki `tools/call` success frame: `event:` line, trailing blank line, JSON `\n` literal. */
    private fun deepWikiSuccessBody(): String =
        "event: message\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\"," +
            "\"text\":\"Available pages for modelcontextprotocol/modelcontextprotocol:" +
            "\\n\\n- 1 Overview\\n- 2 Protocol Specification\"}]}}\n\n"
}
