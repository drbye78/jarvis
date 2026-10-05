package com.jarvis.assistant.home.net.okhttp

import com.jarvis.assistant.home.net.HomeHttpTransport
import com.jarvis.assistant.home.net.HomeTransportException
import com.jarvis.assistant.home.net.HomeWebSocket
import com.jarvis.assistant.home.net.HttpResponse
import com.jarvis.assistant.llm.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The production [HomeHttpTransport] + [HomeWebSocket] over OkHttp.
 *
 * Deliberately provider-AGNOSTIC: it knows nothing about Home Assistant, so the
 * same adapter can serve a later Yandex/Tuya backend. The backend owns URL
 * policy, auth headers and payload shapes; this class only moves bytes.
 *
 * HTTP:
 *  - Redirects are refused on both HTTP and HTTPS (`followRedirects(false)` /
 *    `followSslRedirects(false)`), exactly like the MCP transport: a reachable
 *    server must not be able to 302 the client into an SSRF pivot.
 *  - A non-2xx reply is returned as an [HttpResponse] — the backend classifies
 *    it (401 → AUTH, other → FAILED); it is never turned into an exception.
 *  - A network-class failure is wrapped in [HomeTransportException] so the
 *    backend can report [com.jarvis.assistant.home.HomeError.UNREACHABLE].
 *  - `CancellationException` is rethrown untouched; the shared `Call.await()`
 *    cancels the socket, so barge-in aborts an in-flight request.
 *
 * WebSocket:
 *  - Raw text frames are published to [messages] (a hot shared flow with a
 *    small overflow buffer, so a slow collector drops the OLDEST frame rather
 *    than blocking OkHttp's reader thread).
 *  - [connect]/[send]/[close] check the caller's job so a cancelled coroutine
 *    never starts or continues to drive a socket; [close] is non-blocking and
 *    safe to call from a `finally` (including a cancelled one).
 *
 * [client] is the caller's OkHttp client; a derived client is configured with
 * bounded timeouts and redirects disabled, so the shared graph client is never
 * mutated.
 */
class OkHttpHomeTransport(client: OkHttpClient) : HomeHttpTransport, HomeWebSocket {

    private val httpClient: OkHttpClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private val frameFlow = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = FRAME_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    @Volatile
    private var socket: WebSocket? = null

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse =
        execute(builder(url, headers).get().build())

    override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse =
        execute(builder(url, headers).post(body.toRequestBody(JSON_MEDIA_TYPE)).build())

    private fun builder(url: String, headers: Map<String, String>): Request.Builder =
        Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }

    private suspend fun execute(request: Request): HttpResponse = try {
        httpClient.newCall(request).await().use(::toResponse)
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        throw HomeTransportException("home transport request failed", e)
    }

    private fun toResponse(response: Response): HttpResponse = HttpResponse(
        status = response.code,
        body = response.body?.string().orEmpty(),
        headers = response.headers.toMultimap()
            .entries
            .associate { it.key.lowercase() to it.value.firstOrNull().orEmpty() },
    )

    override suspend fun connect(url: String, headers: Map<String, String>) {
        currentCoroutineContext().ensureActive()
        close()
        socket = httpClient.newWebSocket(builder(url, headers).build(), listener)
    }

    override fun messages(): Flow<String> = frameFlow.asSharedFlow()

    override suspend fun send(text: String) {
        currentCoroutineContext().ensureActive()
        val current = socket ?: throw HomeTransportException("home websocket is not connected")
        if (!current.send(text)) throw HomeTransportException("home websocket send failed")
    }

    override suspend fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            frameFlow.tryEmit(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // The socket is dead; clear it so a later send fails fast instead of
            // silently queueing onto a closed connection.
            if (socket === webSocket) socket = null
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
        const val FRAME_BUFFER = 64
        const val NORMAL_CLOSURE = 1000
    }
}
