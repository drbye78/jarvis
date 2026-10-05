package com.jarvis.assistant.home.net

import kotlinx.coroutines.flow.Flow

/**
 * The transport edge seam for the smart-home lane.
 *
 * Kept deliberately free of OkHttp (and every other concrete HTTP client): the
 * production wiring is the later transport lane's job, while JVM tests inject
 * in-memory fakes. This is what keeps the `home/` core Android-free and
 * JVM-testable.
 */

/** A minimal HTTP response. [headers] keys are lower-cased by convention. */
data class HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap(),
)

/** One-shot HTTP verbs; each call is independent and cancellable. */
interface HomeHttpTransport {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String,
    ): HttpResponse
}

/**
 * A push channel for providers that stream state (HA's websocket API).
 * [messages] is a cold flow of raw text frames; [send] and [close] are
 * suspend so the caller's cancellation (barge-in) tears the socket down.
 */
interface HomeWebSocket {
    suspend fun connect(url: String, headers: Map<String, String> = emptyMap())

    fun messages(): Flow<String>

    suspend fun send(text: String)

    suspend fun close()
}
