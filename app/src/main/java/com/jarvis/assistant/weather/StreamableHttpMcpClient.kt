package com.jarvis.assistant.weather

import com.jarvis.assistant.mcp.McpCall2
import com.jarvis.assistant.mcp.McpClient
import okhttp3.OkHttpClient
import com.jarvis.assistant.mcp.StreamableHttpMcpClient as GeneralMcpClient

/**
 * Weather-lane adapter over the general [com.jarvis.assistant.mcp.McpClient].
 *
 * The weather lane only needs `tools/call` plus three coarse classifications,
 * so the richer protocol model is mapped DOWN here:
 *
 *  - `Ok` → [McpCall.Ok] (a tool-level `isError` is still an Ok round trip).
 *  - transport failure → [McpCall.Unreachable] (the failover trigger).
 *  - an unusable reachable answer → [McpCall.BadResponse].
 *
 * A JSON-RPC error is kept faithful to the OLD weather behavior: on a 2xx body
 * it arrived as an `isError` tool result, while a non-2xx reply (even one
 * carrying an error object) was an unusable [McpCall.BadResponse]. The
 * [McpCall2.ProtocolError.httpStatus] distinguishes the two without changing
 * [ProjectEolWeatherClient]'s failover semantics.
 */
class StreamableHttpMcpClient(
    httpClient: OkHttpClient,
    endpointUrl: String,
) : McpToolClient {

    private val delegate: McpClient = GeneralMcpClient(
        httpClient = httpClient,
        endpointUrl = endpointUrl,
    )

    override suspend fun callTool(name: String, argumentsJson: String): McpCall =
        toWeather(delegate.callTool(name, argumentsJson))

    private fun toWeather(call: McpCall2): McpCall = when (call) {
        is McpCall2.Ok -> McpCall.Ok(McpToolResult(call.result.text, call.result.isError))
        McpCall2.Unreachable -> McpCall.Unreachable
        McpCall2.BadResponse -> McpCall.BadResponse
        is McpCall2.ProtocolError -> when (call.httpStatus) {
            null -> protocolErrorAsToolResult(call)
            in 200..299 -> protocolErrorAsToolResult(call)
            else -> McpCall.BadResponse
        }
    }

    private fun protocolErrorAsToolResult(call: McpCall2.ProtocolError): McpCall =
        McpCall.Ok(McpToolResult("${call.code}: ${call.message}", isError = true))
}
