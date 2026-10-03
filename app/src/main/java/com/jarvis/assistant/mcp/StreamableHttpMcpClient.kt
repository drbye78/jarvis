package com.jarvis.assistant.mcp

import com.jarvis.assistant.llm.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger

/**
 * General MCP "Streamable HTTP" JSON-RPC client.
 *
 * One POST is one JSON-RPC message; the reply may be plain JSON or an SSE
 * stream (both accepted — see [McpResultParser]). The protocol details a
 * real server enforces are handled here:
 *
 *  - **Version.** The negotiated [protocolVersion] travels both in the
 *    `initialize` body and on the `MCP-Protocol-Version` header.
 *  - **Session.** An `Mcp-Session-Id` response header is captured and echoed on
 *    every subsequent request. A server that never issues one is NOT an error
 *    (several are stateless); only `initialize` triggers the follow-up
 *    `notifications/initialized`.
 *  - **Discovery.** [listTools] follows `nextCursor`, accumulating descriptors
 *    up to [MAX_TOOL_PAGES] as a hard stop against a looping server.
 *  - **Non-2xx bodies.** The body of a non-2xx reply is still parsed: a
 *    JSON-RPC `error` object surfaces as [McpCall2.ProtocolError] with its code,
 *    so a 400 `-32600` does not look like an opaque 500.
 *
 * A rejected un-initialized session is [initialize]d and the ORIGINAL call is
 * retried exactly once. Content is never logged; only the tool NAME travels
 * through the caller.
 */
class StreamableHttpMcpClient(
    private val httpClient: OkHttpClient,
    private val endpointUrl: String,
    private val protocolVersion: String = DEFAULT_PROTOCOL_VERSION,
    private val clientName: String = "jarvis",
    private val clientVersion: String = "1.0",
    /**
     * Optional per-server auth. The header is added to EVERY request
     * (`initialize`, `notifications/initialized`, `tools/list`, `tools/call`)
     * only when BOTH parts are non-blank; the VALUE is never logged. Default
     * null preserves the original unauthenticated behavior (the weather lane).
     */
    private val authHeaderName: String? = null,
    private val authHeaderValue: String? = null,
    /**
     * Optional connect-time DNS policy (see [McpDnsGuard]). When supplied, the
     * client derives a per-server OkHttp client from [httpClient] — same
     * connection pool and dispatcher, with the guard attached — and never
     * mutates the SHARED client (which serves LLM/TTS/gRPC). Null keeps the
     * exact previous behavior.
     */
    private val dns: Dns? = null,
) : McpClient {

    private val json = Json { ignoreUnknownKeys = true }

    /** The client used for calls; a DNS-guarded derivative when one was requested. */
    private val requestClient: OkHttpClient =
        if (dns != null) httpClient.newBuilder().dns(dns).build() else httpClient

    @Volatile
    private var sessionId: String? = null

    private val nextId = AtomicInteger(1)

    override suspend fun initialize(): McpCall2 {
        val result = toCall(perform(initializeBody()))
        if (result is McpCall2.Ok) notifyInitialized()
        return result
    }

    override suspend fun listTools(): McpToolsCall {
        val tools = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        var pages = 0
        while (pages < MAX_TOOL_PAGES) {
            when (val call = toToolsCall(perform(toolsListBody(cursor)))) {
                is McpToolsCall.Ok -> {
                    tools += call.page.tools
                    cursor = call.page.nextCursor
                    if (cursor.isNullOrBlank()) break
                }
                else -> return call
            }
            pages++
        }
        // The cap is a hard stop: whatever was accumulated is returned and no
        // cursor is surfaced, so a looping server cannot be re-entered.
        return McpToolsCall.Ok(McpToolsPage(tools, nextCursor = null))
    }

    override suspend fun callTool(name: String, argumentsJson: String): McpCall2 {
        val body = toolCallBody(name, argumentsJson)
        val first = toCall(perform(body))
        if (!wantsInitialize(first)) return first
        // The server wants a session first. Initialize, then re-issue the
        // ORIGINAL call — returning the initialize reply would look like a
        // successful tool result with no payload.
        val initialized = initialize()
        if (initialized !is McpCall2.Ok) return initialized
        return toCall(perform(body))
    }

    override suspend fun close() {
        sessionId = null
    }

    private suspend fun notifyInitialized() {
        // A JSON-RPC notification has no id and no reply to consume; a server
        // that answers 202 with an empty body is normal, so the result is
        // deliberately ignored (a transport failure here is not fatal either).
        perform(initializedNotificationBody())
    }

    /** One round trip, with the session id captured from ANY response header. */
    private suspend fun perform(body: String): Raw = try {
        val response = requestClient.newCall(request(body)).await()
        response.use { resp ->
            resp.header(SESSION_HEADER)?.takeIf { it.isNotBlank() }?.let { sessionId = it }
            Raw.Body(
                payload = resp.body?.string().orEmpty(),
                contentType = resp.header("Content-Type"),
                isSuccessful = resp.isSuccessful,
                status = resp.code,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Transport failure (timeout, DNS, TLS, malformed body): the caller
        // reports one honest "unreachable" outcome; there is no partial result
        // to salvage.
        Raw.Unreachable
    }

    private fun toCall(raw: Raw): McpCall2 = when (val classified = classify(raw)) {
        Classified.Unreachable -> McpCall2.Unreachable
        Classified.BadResponse -> McpCall2.BadResponse
        is Classified.Parsed -> when (val envelope = classified.envelope) {
            is McpEnvelope.ProtocolError ->
                McpCall2.ProtocolError(envelope.code, envelope.message, classified.status)
            is McpEnvelope.Result -> McpCall2.Ok(McpResultParser.parseToolResult(envelope))
            McpEnvelope.Malformed -> McpCall2.BadResponse
        }
    }

    private fun toToolsCall(raw: Raw): McpToolsCall = when (val classified = classify(raw)) {
        Classified.Unreachable -> McpToolsCall.Unreachable
        Classified.BadResponse -> McpToolsCall.BadResponse
        is Classified.Parsed -> when (val envelope = classified.envelope) {
            is McpEnvelope.ProtocolError ->
                McpToolsCall.ProtocolError(envelope.code, envelope.message, classified.status)
            is McpEnvelope.Result -> McpToolsCall.Ok(McpResultParser.parseToolsList(envelope))
            McpEnvelope.Malformed -> McpToolsCall.BadResponse
        }
    }

    /**
     * Shared non-2xx handling: a blank/unusable body is [Classified.BadResponse];
     * a reachable server's JSON-RPC error is [Classified.Parsed] (carrying the
     * HTTP status so a 400 `-32600` is distinguishable from a 500); a 2xx
     * envelope is [Classified.Parsed] for the caller to interpret.
     */
    private fun classify(raw: Raw): Classified = when (raw) {
        Raw.Unreachable -> Classified.Unreachable
        is Raw.Body -> classifyBody(raw)
    }

    private fun classifyBody(body: Raw.Body): Classified {
        if (body.payload.isBlank()) return Classified.BadResponse
        return when (val envelope = McpResultParser.parseEnvelope(body.payload, body.contentType)) {
            is McpEnvelope.ProtocolError -> Classified.Parsed(envelope, body.status)
            is McpEnvelope.Result ->
                if (body.isSuccessful) Classified.Parsed(envelope, body.status) else Classified.BadResponse
            McpEnvelope.Malformed -> Classified.BadResponse
        }
    }

    private fun wantsInitialize(call: McpCall2): Boolean = when (call) {
        is McpCall2.ProtocolError -> call.code == SESSION_NOT_INITIALIZED
        is McpCall2.Ok -> call.result.isError && looksUninitialized(call.result.text)
        else -> false
    }

    private fun looksUninitialized(body: String): Boolean =
        SIGNAL_UNINITIALIZED.any { body.contains(it, ignoreCase = true) }

    private fun request(body: String): Request {
        val builder = Request.Builder()
            .url(endpointUrl)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", protocolVersion)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
        sessionId?.let { builder.header(SESSION_HEADER, it) }
        if (!authHeaderName.isNullOrBlank() && !authHeaderValue.isNullOrBlank()) {
            builder.header(authHeaderName, authHeaderValue)
        }
        return builder.build()
    }

    private fun toolCallBody(name: String, argumentsJson: String): String =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive(JSON_RPC_VERSION))
            put("id", JsonPrimitive(nextId.getAndIncrement()))
            put("method", JsonPrimitive("tools/call"))
            put(
                "params",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("arguments", json.parseToJsonElement(argumentsJson))
                },
            )
        }.toString()

    private fun toolsListBody(cursor: String?): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive(JSON_RPC_VERSION))
        put("id", JsonPrimitive(nextId.getAndIncrement()))
        put("method", JsonPrimitive("tools/list"))
        put(
            "params",
            buildJsonObject {
                cursor?.takeIf { it.isNotBlank() }?.let { put("cursor", JsonPrimitive(it)) }
            },
        )
    }.toString()

    private fun initializeBody(): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive(JSON_RPC_VERSION))
        put("id", JsonPrimitive(nextId.getAndIncrement()))
        put("method", JsonPrimitive("initialize"))
        put(
            "params",
            buildJsonObject {
                put("protocolVersion", JsonPrimitive(protocolVersion))
                put("capabilities", buildJsonObject {})
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", JsonPrimitive(clientName))
                        put("version", JsonPrimitive(clientVersion))
                    },
                )
            },
        )
    }.toString()

    private fun initializedNotificationBody(): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive(JSON_RPC_VERSION))
        put("method", JsonPrimitive("notifications/initialized"))
    }.toString()

    private sealed interface Raw {
        class Body(
            val payload: String,
            val contentType: String?,
            val isSuccessful: Boolean,
            val status: Int,
        ) : Raw

        data object Unreachable : Raw
    }

    /** A parsed, transport-classified reply before it is read as a call result. */
    private sealed interface Classified {
        class Parsed(val envelope: McpEnvelope, val status: Int) : Classified
        data object Unreachable : Classified
        data object BadResponse : Classified
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val JSON_RPC_VERSION = "2.0"
        const val SESSION_HEADER = "Mcp-Session-Id"
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
        const val SESSION_NOT_INITIALIZED = -32002
        const val MAX_TOOL_PAGES = 8
        val SIGNAL_UNINITIALIZED = listOf("initialize", "session", "not initialized")
    }
}
