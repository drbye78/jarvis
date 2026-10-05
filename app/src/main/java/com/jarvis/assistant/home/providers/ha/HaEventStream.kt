package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeStateChange
import com.jarvis.assistant.home.net.HomeWebSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The Home Assistant push stream: one `subscribe_events` subscription over a
 * DEDICATED [webSocket] (never the discovery socket — OkHttp's transport holds
 * a single socket and `connect()` closes the previous one, so sharing would
 * silently tear one of the two down).
 *
 * The flow is COLD: each collector opens its own subscription, filters to the
 * curated [HomeDeviceKey] set inside this class (uncurated frames never leave
 * it), maps `state_changed` into a content-free [HomeStateChange], and tears
 * the socket down in `finally`. Cancellation is rethrown so a caller's stop
 * closes the socket.
 */
internal class HaEventStream(
    private val webSocket: HomeWebSocket,
    private val baseUrl: () -> String,
    private val token: () -> String?,
    private val json: Json,
    private val nowMs: () -> Long,
    private val frameTimeoutMs: Long = 2_000,
    private val maxFrames: Int = 256,
) {

    fun events(keys: Set<HomeDeviceKey>): Flow<HomeStateChange> = channelFlow {
        val normalized = baseUrl().trim().trimEnd('/')
        val tokenValue = token()?.trim()?.takeIf { it.isNotEmpty() } ?: return@channelFlow
        val socketUrl = HaWs.webSocketUrl(normalized) ?: return@channelFlow

        val frames = Channel<String>(Channel.UNLIMITED)
        // UNDISPATCHED: subscribe to the (hot) message flow BEFORE connect() so
        // an early auth frame cannot race past the collector.
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { collectFrames(frames) }
        try {
            webSocket.connect(socketUrl, mapOf("Authorization" to "Bearer $tokenValue"))
            if (!HaWs.authenticate(webSocket, frames, json, tokenValue, frameTimeoutMs, maxFrames)) {
                Timber.d("Home awareness: HA websocket auth failed")
                return@channelFlow
            }
            webSocket.send(HaWs.requestBody(SUBSCRIBE_EVENTS, SUBSCRIBE_ID))
            if (!awaitSubscribed(frames)) {
                Timber.d("Home awareness: HA subscribe_events was not acknowledged")
                return@channelFlow
            }
            val mapper = HaCapabilityMapper()
            while (true) {
                val frame = frames.receive()
                decode(frame, keys, mapper)?.let { send(it) }
            }
        } finally {
            collector.cancel()
            runCatching { webSocket.close() }
        }
    }

    private suspend fun collectFrames(frames: Channel<String>) {
        try {
            webSocket.messages().collect { frames.send(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A dead socket ends the stream; the caller's reconnect loop retries.
            Timber.d("Home awareness: HA websocket collector ended (%s)", e::class.java.simpleName)
        }
    }

    /**
     * Decode one frame into a curated [HomeStateChange], or null when it is not
     * a tracked transition (not a `state_changed` frame, an uncurated entity, or
     * a removal with no new state).
     */
    private fun decode(
        frame: String,
        keys: Set<HomeDeviceKey>,
        mapper: HaCapabilityMapper,
    ): HomeStateChange? {
        val changed = HaEvents.stateChanged(frame, json) ?: return null
        val key = HaEvents.keyOf(changed.entityId)
        if (key !in keys) return null
        val newEntity = changed.new ?: return null
        val now = nowMs()
        val newState = HaEvents.toState(key, newEntity, now) ?: return null
        return HomeStateChange(
            key = key,
            kind = HaEvents.kindOf(newEntity, mapper),
            old = HaEvents.toState(key, changed.old, now),
            new = newState,
            atMs = now,
        )
    }

    private suspend fun awaitSubscribed(frames: Channel<String>): Boolean {
        repeat(maxFrames) {
            val frame = withTimeoutOrNull(frameTimeoutMs) { frames.receive() } ?: return false
            if (HaEvents.resultOk(frame, SUBSCRIBE_ID, json)) return true
            if (HaEvents.resultFailed(frame, SUBSCRIBE_ID, json)) return false
        }
        return false
    }

    private companion object {
        const val SUBSCRIBE_EVENTS = "subscribe_events"
        const val SUBSCRIBE_ID = 1
    }
}
