package com.jarvis.assistant.llm

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.wire.GigaChatChatRequest
import com.jarvis.assistant.wire.toGigaChatNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.io.IOException

/**
 * GigaChat **native** (`/v2/chat/completions`) streaming client — the
 * LIVE-VERIFIED contract. /v1 is a legacy shim that silently ignores `tools`,
 * so this client uses [endpoint] (the `/v2` URL the graph injects).
 *
 * It emits the same [LlmChunk] vocabulary as [SseLlmClient], so `TurnRunner`
 * needs no happy-path change:
 * - a web-search turn arrives as `Text` + `Done` with ZERO pending tool calls
 *   (the server runs web_search and rewrites the answer in prose);
 * - a client call arrives as a fully-formed `FunctionCallComplete`.
 *
 * Cancellation mirrors [SseLlmClient] (M4/M5): the blocking read runs in an IO
 * child, `awaitClose` cancels the OkHttp [Call] to unblock it, and
 * [SseStream] rethrows a genuine transport failure while treating a cancelled
 * call's [IOException] as a silent exit.
 */
class GigaChatNativeClient(
    private val tokenManager: TokenManager,
    private val httpClient: OkHttpClient,
    private val endpoint: String,
    private val defaultModel: String,
    private val webSearchEnabled: Boolean = true,
    private val timezone: () -> String? = { null },
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = channelFlow {
        var call: Call? = null

        // Blocking producer runs in a child coroutine so awaitClose stays reachable.
        launch(Dispatchers.IO) {
            try {
                val body = request.toGigaChatNative(
                    model = request.model ?: defaultModel,
                    timezone = timezone(),
                    webSearch = webSearchEnabled,
                )
                val bodyJson = json.encodeToString(GigaChatChatRequest.serializer(), body)
                val token = tokenManager.getGigaChatToken()
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val httpRequest = Request.Builder()
                    .url(endpoint)
                    .header("Authorization", "Bearer $token")
                    .post(bodyJson.toRequestBody(mediaType))
                    .header("Accept", "text/event-stream")
                    .build()
                val httpCall = httpClient.newCall(httpRequest)
                call = httpCall

                // Cancellable open (M5): barge-in during connect/headers aborts
                // the in-flight request instead of waiting for the timeout.
                val response = httpCall.await()
                try {
                    if (!response.isSuccessful) {
                        val err = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        // Bounded length only — the raw body can echo token/PII
                        // material and reaches the rotating file log.
                        Timber.e(
                            "GigaChat native request failed: HTTP %d, body length=%d",
                            response.code,
                            err.length,
                        )
                        close(LlmHttpException(response.code))
                        return@launch
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        close(RuntimeException("GigaChat native returned an empty body"))
                        return@launch
                    }

                    val parser = GigaChatSseParser(request.tools.map { it.name }.toSet())
                    SseStream.read(source, isCancelled = { httpCall.isCanceled() }) { event ->
                        val chunks = parser.parse(event.name, event.data)
                        chunks.forEach { send(it) }
                        chunks.any { it is LlmChunk.Done }
                    }
                    if (!httpCall.isCanceled()) {
                        parser.finish().forEach { send(it) }
                    }
                    close()
                } finally {
                    runCatching { response.close() }
                }
            } catch (e: CancellationException) {
                throw e // never mask cancellation as a stream error
            } catch (e: IOException) {
                if (call?.isCanceled() != true) close(e)
            } catch (e: Exception) {
                if (call?.isCanceled() != true) close(e)
            }
        }

        awaitClose { call?.cancel() }
    }
}
