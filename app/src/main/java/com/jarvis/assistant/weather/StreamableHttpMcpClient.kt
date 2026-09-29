package com.jarvis.assistant.weather

import com.jarvis.assistant.llm.await
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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
 * The narrow MCP surface the weather lane needs: call a named tool with a JSON
 * argument object. A test seam — [StreamableHttpMcpClient] is the only
 * production implementation, and JVM tests substitute a fake so no unit test
 * ever opens a socket.
 */
interface McpToolClient {
    /** Returns null when the endpoint is unreachable or the reply is unusable. */
    suspend fun callTool(name: String, argumentsJson: String): McpToolResult?
}

/**
 * MCP "Streamable HTTP" JSON-RPC client, scoped to `tools/call`.
 *
 * Deliberately minimal: no session management and no SSE *stream* — the weather
 * endpoint replies to a single POST, so one request is one response. The two
 * protocol details that DO matter are handled:
 *
 *  - **The response is not always `application/json`.** MCP servers may answer
 *    with `text/event-stream` (SSE); both shapes are accepted and the JSON-RPC
 *    envelope is extracted from whichever arrives.
 *  - **`initialize` is optional in practice but required by the spec.** The
 *    Project EOL endpoint answers `tools/call` on a fresh connection, so the
 *    common path is ONE request. If a server ever rejects an un-initialized
 *    session, the client initializes and retries once instead of failing the
 *    turn — see [SIGNAL_UNINITIALIZED].
 *
 * Content is never logged; only the tool NAME and a failure reason.
 */
class StreamableHttpMcpClient(
    private val httpClient: OkHttpClient,
    private val endpointUrl: String,
    private val protocolVersion: String = "2024-11-05",
    private val clientName: String = "jarvis",
    private val clientVersion: String = "1.0",
) : McpToolClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun callTool(name: String, argumentsJson: String): McpToolResult? =
        post(toolCallBody(name, argumentsJson, requestId = 1))

    /** One round trip. A rejected un-initialized session initializes, then RETRIES the call. */
    private suspend fun post(body: String): McpToolResult? {
        val first = exchange(body) ?: return null
        if (!first.isError || !looksUninitialized(first.text)) return first
        // The server wants a session first. Initialize, then re-issue the
        // ORIGINAL call — returning the initialize reply would look like a
        // successful tool result with no payload.
        val initialized = exchange(initializeBody()) ?: return null
        if (initialized.isError) return initialized
        return exchange(body)
    }

    private suspend fun exchange(body: String): McpToolResult? = try {
        val response = httpClient.newCall(
            Request.Builder()
                .url(endpointUrl)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).await()
        response.use { resp ->
            val payload = resp.body?.string()
            // A non-2xx reply carries no JSON-RPC envelope: degrade to null so
            // the caller reports one honest "unreachable" outcome rather than
            // inventing a tool-level error from a transport one.
            if (!resp.isSuccessful || payload.isNullOrBlank()) {
                null
            } else {
                parse(payload, resp.header("Content-Type"))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Transport failure (timeout, DNS, TLS, malformed body): the caller
        // reports one honest "unreachable" outcome; there is no partial result
        // to salvage and the reason is not user-actionable.
        null
    }

    /** Extracts the JSON-RPC envelope from either a plain body or an SSE stream. */
    private fun parse(payload: String, contentType: String?): McpToolResult? {
        val envelope = if (contentType?.contains("text/event-stream", ignoreCase = true) == true) {
            sseJson(payload)
        } else {
            payload
        } ?: return null
        return runCatching { json.parseToJsonElement(envelope).jsonObject }.getOrNull()?.let(::toResult)
    }

    /** Last `data:` frame wins — that is the terminal JSON-RPC message. */
    private fun sseJson(payload: String): String? = payload
        .lineSequence()
        .filter { it.startsWith(SSE_DATA_PREFIX) }
        .map { it.removePrefix(SSE_DATA_PREFIX).trim() }
        .filter { it.startsWith("{") }
        .lastOrNull()

    private fun toResult(envelope: JsonObject): McpToolResult {
        envelope["error"]?.let { error ->
            return McpToolResult(error.toString(), isError = true)
        }
        val result = envelope["result"]?.jsonObject
            ?: return McpToolResult("Malformed MCP reply", isError = true)
        val isError = result["isError"]?.jsonPrimitive?.contentOrNull == "true"
        // structuredContent is the machine-readable copy; fall back to the text block.
        result["structuredContent"]?.let { return McpToolResult(it.toString(), isError) }
        val text = result["content"]?.let { content ->
            runCatching {
                content.jsonArray
                    .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .joinToString("")
            }.getOrNull()
        }
        return McpToolResult(text ?: "Empty MCP reply", isError)
    }

    private fun looksUninitialized(body: String): Boolean =
        SIGNAL_UNINITIALIZED.any { body.contains(it, ignoreCase = true) }

    private fun toolCallBody(name: String, argumentsJson: String, requestId: Int): String =
        buildJsonObject {
            put("jsonrpc", JsonPrimitive(JSON_RPC_VERSION))
            put("id", JsonPrimitive(requestId))
            put("method", JsonPrimitive("tools/call"))
            put(
                "params",
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("arguments", json.parseToJsonElement(argumentsJson))
                },
            )
        }.toString()

    private fun initializeBody(): String = buildJsonObject {
        put("jsonrpc", JsonPrimitive(JSON_RPC_VERSION))
        put("id", JsonPrimitive(0))
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

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val JSON_RPC_VERSION = "2.0"
        const val SSE_DATA_PREFIX = "data:"
        val SIGNAL_UNINITIALIZED = listOf("initialize", "session", "not initialized")
    }
}
