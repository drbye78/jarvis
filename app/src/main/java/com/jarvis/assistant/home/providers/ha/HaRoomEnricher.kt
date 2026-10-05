package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.net.HomeWebSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Best-effort room enrichment over the Home Assistant websocket API.
 *
 * Discovery must never depend on the websocket: this helper returns null on ANY
 * failure (connect refused, auth timeout, malformed frame, overall deadline) and
 * the backend then keeps every device with a null room. Cancellation is rethrown
 * so a barge-in tears the socket down.
 *
 * Protocol (HA's `auth` handshake + registry commands):
 *  1. server sends `auth_required`; we reply `{"type":"auth","access_token":…}`;
 *  2. server sends `auth_ok`;
 *  3. `config/entity_registry/list`, `config/device_registry/list` and
 *     `config/area_registry/list`, each a request with its own monotonically
 *     increasing id; we accept only the matching id.
 */
internal class HaRoomEnricher(
    private val webSocket: HomeWebSocket,
    private val json: Json,
    private val frameTimeoutMs: Long = 2_000,
    private val totalTimeoutMs: Long = 6_000,
    private val maxFrames: Int = 64,
) {

    /** entity_id → area name, or null when enrichment is unavailable for any reason. */
    suspend fun roomIndex(baseUrl: String, token: String): Map<String, String>? {
        val socketUrl = webSocketUrl(baseUrl) ?: return null
        return try {
            withTimeoutOrNull(totalTimeoutMs) { fetch(socketUrl, token) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetch(socketUrl: String, token: String): Map<String, String>? = coroutineScope {
        val frames = Channel<String>(Channel.UNLIMITED)
        // UNDISPATCHED: the collector subscribes to the (hot) message flow BEFORE
        // connect() is invoked, so an early auth frame cannot race past it.
        val collector = async(start = CoroutineStart.UNDISPATCHED) { collectFrames(frames) }
        try {
            webSocket.connect(socketUrl, mapOf("Authorization" to "Bearer $token"))
            if (!awaitAuth(frames, token)) return@coroutineScope null
            val entities = request(frames, ENTITY_REGISTRY, REQUEST_ENTITY) ?: return@coroutineScope null
            val devices = request(frames, DEVICE_REGISTRY, REQUEST_DEVICE) ?: return@coroutineScope null
            val areas = request(frames, AREA_REGISTRY, REQUEST_AREA) ?: return@coroutineScope null
            HaPayloads.roomIndex(entities, devices, areas)
        } finally {
            collector.cancel()
            runCatching { webSocket.close() }
        }
    }

    private suspend fun collectFrames(frames: Channel<String>) {
        try {
            webSocket.messages().collect { frames.send(it) }
        } catch (e: CancellationException) {
            // Our own collector.cancel() (or a barge-in): ends the collector.
            throw e
        } catch (_: Exception) {
            // A dead socket ends enrichment; the caller degrades to name-only.
        }
    }

    private suspend fun awaitAuth(frames: Channel<String>, token: String): Boolean {
        repeat(maxFrames) {
            val frame = withTimeoutOrNull(frameTimeoutMs) { frames.receive() } ?: return false
            when (HaPayloads.frameType(frame, json)) {
                "auth_required" -> webSocket.send(authBody(token))
                "auth_ok" -> return true
                "auth_invalid" -> return false
                else -> Unit
            }
        }
        return false
    }

    private suspend fun request(frames: Channel<String>, type: String, id: Int): JsonArray? {
        webSocket.send(requestBody(type, id))
        repeat(maxFrames) {
            val frame = withTimeoutOrNull(frameTimeoutMs) { frames.receive() } ?: return null
            HaPayloads.resultArray(frame, id, json)?.let { return it }
        }
        return null
    }

    private fun authBody(token: String): String = buildJsonObject {
        put("type", "auth")
        put("access_token", token)
    }.toString()

    private fun requestBody(type: String, id: Int): String = buildJsonObject {
        put("id", id)
        put("type", type)
    }.toString()

    private fun webSocketUrl(baseUrl: String): String? {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.startsWith("https://") ->
                "wss://" + normalized.removePrefix("https://") + WS_PATH

            normalized.startsWith("wss://") -> normalized + WS_PATH
            else -> null
        }
    }

    private companion object {
        const val WS_PATH = "/api/websocket"
        const val ENTITY_REGISTRY = "config/entity_registry/list"
        const val DEVICE_REGISTRY = "config/device_registry/list"
        const val AREA_REGISTRY = "config/area_registry/list"
        const val REQUEST_ENTITY = 1
        const val REQUEST_DEVICE = 2
        const val REQUEST_AREA = 3
    }
}
