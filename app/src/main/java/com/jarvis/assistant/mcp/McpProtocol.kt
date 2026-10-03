package com.jarvis.assistant.mcp

import kotlinx.serialization.json.JsonObject

/**
 * A parsed JSON-RPC envelope: the terminal message of ONE MCP exchange.
 *
 * The three cases stay distinct on purpose. A `tools/call` that fails at the
 * PROTOCOL level (unsupported version, unknown method, un-initialized session)
 * is not a tool result at all, and [ProtocolError.code] stays readable so a
 * caller can branch on the exact failure instead of seeing a flat boolean.
 */
sealed interface McpEnvelope {
    /** The envelope's `result` member was present. [payload] is the WHOLE envelope. */
    data class Result(val payload: JsonObject) : McpEnvelope

    /** A top-level JSON-RPC `error` object: `{code, message, data?}`. */
    data class ProtocolError(val code: Int, val message: String, val data: String? = null) : McpEnvelope

    /** Not JSON, not an envelope, or neither `result` nor `error`. */
    data object Malformed : McpEnvelope
}

/**
 * The payload of a `tools/call`: [text] is the machine-readable copy
 * (`structuredContent` stringified when the server sends it, else the joined
 * `content[].text`), plus the tool's own [isError] flag.
 */
data class McpToolResult2(val text: String, val isError: Boolean)

/** One entry of `tools/list`. [inputSchema] is raw JSON kept as a STRING. */
data class McpToolDescriptor(val name: String, val description: String?, val inputSchema: String)

/** One page of `tools/list`; [nextCursor] is non-null when more pages exist. */
data class McpToolsPage(val tools: List<McpToolDescriptor>, val nextCursor: String?)

/**
 * A classified outcome of `initialize` / `tools/call`. [Unreachable] is a
 * transport failure (DNS/TCP/TLS/timeout); [BadResponse] is an ANSWER from a
 * reachable server that could not be used; [ProtocolError] is a reachable
 * server's JSON-RPC error and keeps its [code] (and the HTTP status it arrived
 * on, when it came with a non-2xx reply).
 */
sealed interface McpCall2 {
    data class Ok(val result: McpToolResult2) : McpCall2
    data class ProtocolError(val code: Int, val message: String, val httpStatus: Int? = null) : McpCall2
    data object Unreachable : McpCall2
    data object BadResponse : McpCall2
}

/** A classified outcome of the paged `tools/list`. */
sealed interface McpToolsCall {
    data class Ok(val page: McpToolsPage) : McpToolsCall
    data class ProtocolError(val code: Int, val message: String, val httpStatus: Int? = null) : McpToolsCall
    data object Unreachable : McpToolsCall
    data object BadResponse : McpToolsCall
}

/**
 * The general, transport-level MCP surface shared by the weather lane and the
 * forthcoming multi-server feature. [StreamableHttpMcpClient] is the only
 * production implementation; JVM tests substitute a fake so no unit test opens
 * a socket.
 */
interface McpClient {
    /** Establishes a session (and, with it, a session id when the server issues one). */
    suspend fun initialize(): McpCall2

    /** All tools, following `nextCursor` up to a bounded page count. */
    suspend fun listTools(): McpToolsCall

    /** A single `tools/call`; [argumentsJson] must be a JSON object literal. */
    suspend fun callTool(name: String, argumentsJson: String): McpCall2

    /** Drops any captured session id; the next call starts fresh. */
    suspend fun close()
}
