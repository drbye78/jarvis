package com.jarvis.assistant.llm

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.wire.toWire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** Streaming chat-completions client contract. */
interface LlmClient {
    fun chatStream(request: ChatRequest): Flow<LlmChunk>
}

/**
 * Shared SSE implementation for OpenAI-compatible endpoints.
 *
 * Cancellation correctness (original defect #7): the blocking line-reading
 * loop runs in a child coroutine on [Dispatchers.IO], and `awaitClose`
 * cancels the OkHttp [Call]. Because `Call.cancel()` closes the underlying
 * socket, the blocked `readUtf8Line()` unblocks with an IOException and the
 * producer exits promptly. Barge-in no longer leaks a request until the read
 * timeout.
 */
abstract class SseLlmClient(
    protected val httpClient: OkHttpClient,
) : LlmClient {

    protected val json = Json { ignoreUnknownKeys = true }

    /**
     * Subclasses provide the URL + auth headers (as an unfinished builder).
     * This is a suspend function because profiles like GigaChat must fetch an
     * OAuth token first.
     */
    protected abstract suspend fun newRequest(request: ChatRequest, bodyJson: String): Request.Builder

    /**
     * Subclasses may override the request before serialization (e.g. inject
     * a default model).
     */
    protected open fun customize(request: ChatRequest): ChatRequest = request

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = channelFlow {
        var call: Call? = null

        // Blocking producer runs in a child coroutine so awaitClose stays reachable.
        val producer = launch(Dispatchers.IO) {
            var acc = mutableMapOf<Int, ToolCallAccum>()
            var toolCallsFinalized = false

            fun finalizeToolCalls() {
                if (toolCallsFinalized) return
                toolCallsFinalized = true
                acc.toSortedMap().forEach { (_, a) ->
                    val name = a.name ?: return@forEach
                    trySend(
                        LlmChunk.FunctionCallComplete(
                            ToolCall(
                                id = a.id ?: UUID.randomUUID().toString(),
                                function = FunctionCall(name, a.args.toString()),
                            )
                        )
                    )
                }
            }

            try {
                val wire = customize(request).toWire()
                val bodyJson = json.encodeToString(
                    com.jarvis.assistant.wire.WireChatRequest.serializer(),
                    wire,
                )
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val httpRequest = newRequest(request, bodyJson)
                    .post(bodyJson.toRequestBody(mediaType))
                    .header("Accept", "text/event-stream")
                    .build()
                val httpCall = httpClient.newCall(httpRequest)
                call = httpCall

                // Cancellable open (M5): barge-in during connect/headers aborts
                // the in-flight request instead of waiting for the timeout.
                val response = httpCall.await()
                try {
                    // Body lifecycle (M4): closed on EVERY exit below — normal
                    // EOF, [DONE], error, and cancellation — via this finally.
                    if (!response.isSuccessful) {
                        val err = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        close(RuntimeException("LLM request failed (HTTP ${response.code}): $err"))
                        return@launch
                    }
                    val source = response.body?.source()
                        ?: run {
                            close(RuntimeException("LLM returned an empty body"))
                            return@launch
                        }

                    while (true) {
                        // Stop consuming mid-stream as soon as we are cancelled.
                        ensureActive()
                        val line = try {
                            source.readUtf8Line() ?: break // EOF
                        } catch (e: IOException) {
                            if (httpCall.isCanceled()) return@launch // barge-in: stop silently
                            close(e)
                            return@launch
                        }

                        val data = SseParser.dataPayload(line) ?: continue
                        if (SseParser.isDone(data)) {
                            finalizeToolCalls()
                            trySend(LlmChunk.Done)
                            break
                        }

                        val parsed = SseParser.parseChunk(json, data) ?: continue

                        parsed.text?.takeIf { it.isNotEmpty() }?.let { trySend(LlmChunk.Text(it)) }

                        for (d in parsed.toolDeltas) {
                            val a = acc.getOrPut(d.index) { ToolCallAccum(d.index) }
                            if (d.id != null) a.id = d.id
                            if (d.name != null) a.name = d.name
                            a.args.append(d.argsDelta)
                            trySend(LlmChunk.FunctionCallDelta(d.index, d.name, d.argsDelta))
                        }

                        if (parsed.finishReason != null) {
                            finalizeToolCalls()
                            acc = mutableMapOf() // defensive: no double-finalize
                        }
                    }
                    // Normal end ([DONE] or EOF): finalize, emit Done, and
                    // CLOSE the channel — the producer ending alone does not
                    // complete a channelFlow parked in awaitClose, and without
                    // close() every collector hangs until its timeout (the
                    // same defect commit 3999acb fixed in the v3 client).
                    finalizeToolCalls()
                    trySend(LlmChunk.Done)
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

    private class ToolCallAccum(val index: Int) {
        var name: String? = null
        var id: String? = null
        val args = StringBuilder()
    }
}

/**
 * Sber GigaChat profile: OAuth2 client-credentials bearer token
 * (see [TokenManager]) + the device endpoint.
 */
class GigaChatClient(
    private val tokenManager: TokenManager,
    httpClient: OkHttpClient,
    private val endpoint: String,
    private val defaultModel: String,
) : SseLlmClient(httpClient) {

    override suspend fun newRequest(request: ChatRequest, bodyJson: String): Request.Builder {
        val token = tokenManager.getGigaChatToken()
        return Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $token")
    }

    /** GigaChat requires the model field; inject the configured default. */
    override fun customize(request: ChatRequest): ChatRequest =
        if (request.model == null) request.copy(model = defaultModel) else request
}

/**
 * OpenAI-compatible profile: static API key + configurable base URL
 * (e.g. https://api.openai.com/v1, a local llama.cpp server, OpenRouter, ...).
 */
class OpenAiCompatClient(
    httpClient: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val defaultModel: String,
) : SseLlmClient(httpClient) {

    override suspend fun newRequest(request: ChatRequest, bodyJson: String): Request.Builder {
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
    }

    override fun customize(request: ChatRequest): ChatRequest =
        if (request.model == null) request.copy(model = defaultModel) else request
}
