package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.net.HomeWebSocket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Shared Home Assistant websocket HANDSHAKE, used by both the registry
 * enrichment ([HaRoomEnricher]) and the awareness event stream
 * ([HaEventStream]).
 *
 * This is the protocol fact that must not drift between the two: server sends
 * `auth_required`, we reply `{"type":"auth","access_token":…}`, the server
 * answers `auth_ok` (or `auth_invalid`). Each caller keeps its OWN socket and
 * its own request/event loop — only the handshake is shared.
 */
internal object HaWs {

    const val WS_PATH = "/api/websocket"

    /** `https://host` → `wss://host/api/websocket`, or null when not https/wss. */
    fun webSocketUrl(baseUrl: String): String? {
        val normalized = baseUrl.trim().trimEnd('/')
        return when {
            normalized.startsWith("https://") ->
                "wss://" + normalized.removePrefix("https://") + WS_PATH

            normalized.startsWith("wss://") -> normalized + WS_PATH
            else -> null
        }
    }

    fun authBody(token: String): String = buildJsonObject {
        put("type", "auth")
        put("access_token", token)
    }.toString()

    fun requestBody(type: String, id: Int): String = buildJsonObject {
        put("id", id)
        put("type", type)
    }.toString()

    /**
     * Drive the auth handshake over [frames], sending the token when asked.
     * True on `auth_ok`; false on `auth_invalid`, a read timeout, or frame
     * budget exhaustion. Frames that are neither auth type are ignored.
     */
    suspend fun authenticate(
        webSocket: HomeWebSocket,
        frames: Channel<String>,
        json: Json,
        token: String,
        frameTimeoutMs: Long,
        maxFrames: Int,
    ): Boolean {
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
}
