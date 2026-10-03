package com.jarvis.assistant.weather

/**
 * A single MCP tool invocation.
 *
 * [text] is the tool's payload. MCP returns it twice — a `content[]` text block
 * AND (optionally) `structuredContent`; [text] is the JSON the caller parses,
 * preferring `structuredContent` when the server sends it, because that is the
 * copy guaranteed to be machine-readable rather than prose.
 */
data class McpToolResult(val text: String, val isError: Boolean)

/**
 * A classified MCP round-trip result. [Unreachable] is a transport failure
 * (DNS/TCP/TLS/timeout) that may justify weather failover; [BadResponse] is an
 * ANSWER from a reachable server that could not be used (non-2xx, blank body,
 * unparseable envelope) and must NOT trigger failover.
 */
sealed interface McpCall {
    data class Ok(val result: McpToolResult) : McpCall
    data object Unreachable : McpCall
    data object BadResponse : McpCall
}

/**
 * The narrow MCP surface the weather lane needs: call a named tool with a JSON
 * argument object. A test seam — [StreamableHttpMcpClient] is the only
 * production implementation, and JVM tests substitute a fake so no unit test
 * ever opens a socket.
 */
interface McpToolClient {
    /** A classified outcome: [McpCall.Unreachable] only for transport failure. */
    suspend fun callTool(name: String, argumentsJson: String): McpCall
}
