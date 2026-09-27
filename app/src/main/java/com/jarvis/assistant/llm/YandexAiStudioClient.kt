package com.jarvis.assistant.llm

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.wire.YandexError
import com.jarvis.assistant.wire.YandexResponse
import com.jarvis.assistant.wire.YandexResponsesRequest
import com.jarvis.assistant.wire.toYandexResponses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Yandex AI Studio **Responses API** client — the LIVE-VERIFIED contract
 * (`POST {endpoint}/responses`, `Authorization: Api-Key <key>`; NOT Bearer and
 * no OAuth/TokenManager). See `/tmp/opencode/YANDEX_CONTRACT.md`.
 *
 * It emits the same [LlmChunk] vocabulary as the other profiles, so `TurnRunner`
 * needs no happy-path change:
 * - a server-executed web-search turn arrives as `Text` + `Done` with ZERO
 *   pending tool calls (the model runs the search and answers in prose);
 * - a client call arrives as a fully-formed `FunctionCallComplete` whose
 *   `call_id` is preserved for the `function_call_output` round-trip.
 *
 * **Folder resolution is lazy and cached.** The model URI needs the service
 * account's folder id, which is discoverable from `GET {endpoint}/models` (the
 * 2nd URI segment of a `gpt://<folder>/...` id). Resolution order: explicit
 * [manualFolderId] → in-memory [cachedFolderId] → discovery. It runs on the
 * first `chatStream`/`chatOnce`, NEVER in the constructor, so constructing the
 * graph never does I/O. A discovery failure is a typed
 * [YandexFolderResolutionException], never a crash and never an empty answer.
 *
 * Discovery carries its OWN bounded timeout ([folderDiscoveryTimeoutMs],
 * applied as the OkHttp call timeout so it spans connect + headers + body)
 * instead of relying on the shared client defaults, and it is SINGLE-FLIGHT:
 * a [folderMutex] serializes the first concurrent callers, so N simultaneous
 * first turns perform ONE `GET /models` and the rest reuse the cached result.
 * The lock is only taken on a cache miss; the hot (cached) path never locks.
 *
 * Cancellation mirrors [GigaChatNativeClient] (M4/M5): the blocking read runs
 * in an IO child, `awaitClose` cancels the OkHttp [Call] to unblock it, and
 * [SseStream] rethrows a genuine transport failure while treating a cancelled
 * call's [IOException] as a silent exit.
 */
class YandexAiStudioClient(
    private val httpClient: OkHttpClient,
    private val apiKeyProvider: () -> String,
    private val endpoint: String,
    private val defaultModel: String,
    private val manualFolderId: String = "",
    private val webSearchEnabled: Boolean = true,
    /** Dedicated bound for `GET /models`; see the folder-resolution KDoc. */
    private val folderDiscoveryTimeoutMs: Long = DEFAULT_FOLDER_DISCOVERY_TIMEOUT_MS,
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val baseUrl: String = endpoint.trimEnd('/')

    @Volatile
    private var cachedFolderId: String? = null

    /** Single-flight guard for the FIRST folder discovery (cache-miss path only). */
    private val folderMutex = Mutex()

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = channelFlow {
        var call: Call? = null

        // Blocking producer runs in a child coroutine so awaitClose stays reachable.
        launch(Dispatchers.IO) {
            try {
                val body = request.toYandexResponses(modelUri(request.model), webSearchEnabled)
                val bodyJson = json.encodeToString(YandexResponsesRequest.serializer(), body)
                val httpCall = httpClient.newCall(newRequest(bodyJson, acceptEvents = true).build())
                call = httpCall

                // Cancellable open (M5): barge-in during connect/headers aborts
                // the in-flight request instead of waiting for the timeout.
                val response = httpCall.await()
                try {
                    if (!response.isSuccessful) {
                        val raw = runCatching { response.body?.string() }.getOrNull().orEmpty()
                        // Bounded length only — the raw body can echo request
                        // content and reaches the rotating file log.
                        Timber.e(
                            "Yandex AI Studio request failed: HTTP %d, body length=%d",
                            response.code,
                            raw.length,
                        )
                        close(LlmHttpException(response.code, detailFromBody(raw)))
                        return@launch
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        close(RuntimeException("Yandex AI Studio returned an empty body"))
                        return@launch
                    }

                    val parser = YandexSseParser(request.tools.map { it.name }.toSet())
                    SseStream.read(source, isCancelled = { httpCall.isCanceled() }) { event ->
                        val chunks = parser.parse(event.data)
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

    /**
     * Non-streaming completion for the Cognitive Core: a single `POST` with
     * `"stream":false`, then the convenience top-level `output_text` (fallback:
     * the first `output_text` content part). Mirrors the sibling profiles.
     */
    override suspend fun chatOnce(request: ChatRequest): String {
        val body = request.toYandexResponses(modelUri(request.model), webSearchEnabled)
            .copy(stream = false)
        val bodyJson = json.encodeToString(YandexResponsesRequest.serializer(), body)
        val httpRequest = newRequest(bodyJson, acceptEvents = false).build()
        httpClient.newCall(httpRequest).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LlmHttpException(response.code, detailFromBody(raw))
            }
            // A 2xx body we cannot decode is a protocol anomaly, NOT "the model
            // returned no JSON". Silently returning "" here made the extraction
            // worker quarantine a whole batch over one weird payload; the typed
            // IOException is classified as a transport/batch-release instead.
            val parsed = runCatching {
                json.decodeFromString(YandexResponse.serializer(), raw)
            }.getOrNull() ?: throw LlmResponseParseException(
                "Yandex AI Studio returned an undecodable response (HTTP ${response.code})"
            )
            return extractOutputText(parsed)
        }
    }

    private fun newRequest(bodyJson: String, acceptEvents: Boolean): Request.Builder {
        val mediaType = JSON_MEDIA_TYPE.toMediaType()
        return Request.Builder()
            .url("$baseUrl/$RESPONSES_PATH")
            .header(AUTHORIZATION, "Api-Key ${apiKeyProvider()}")
            .post(bodyJson.toRequestBody(mediaType))
            .header("Accept", if (acceptEvents) EVENT_STREAM else JSON_MEDIA_TYPE)
    }

    /** Full model URI, resolving the folder lazily (see class KDoc). */
    private suspend fun modelUri(model: String?): String {
        val name = (model ?: defaultModel).trim()
        if (name.startsWith(GPT_SCHEME)) return name
        return "$GPT_SCHEME${resolveFolderId()}/$name/latest"
    }

    private suspend fun resolveFolderId(): String {
        manualFolderId.trim().takeIf { it.isNotEmpty() }?.let { return it }
        cachedFolderId?.let { return it }
        // Single-flight: a second concurrent first call waits here, then reads
        // the cache the winner just populated. The manual/cached fast paths
        // above never take the lock, so the streaming turn is not blocked.
        return folderMutex.withLock {
            cachedFolderId?.let { return@withLock it }
            discoverFolderId().also { cachedFolderId = it }
        }
    }

    private suspend fun discoverFolderId(): String {
        val request = Request.Builder()
            .url("$baseUrl/$MODELS_PATH")
            .header(AUTHORIZATION, "Api-Key ${apiKeyProvider()}")
            .get()
            .build()
        val call = httpClient.newCall(request)
        // Dedicated bound: the shared client defaults are not a per-discovery
        // contract. OkHttp's call timeout spans connect + headers + body, so a
        // stalled `/models` cannot hang resolution (nor the turn behind it).
        call.timeout().timeout(folderDiscoveryTimeoutMs, TimeUnit.MILLISECONDS)
        val response = try {
            call.await()
        } catch (e: CancellationException) {
            throw e // A8: never mask shutdown/barge-in
        } catch (e: IOException) {
            // Transport/timeout → typed, content-free folder failure. The raw
            // message can embed host/port material and reaches the file log, so
            // it is preserved as the CAUSE (never interpolated into the text).
            throw YandexFolderResolutionException(
                "Yandex model discovery failed (${e.javaClass.simpleName})",
                e,
            )
        }
        return try {
            folderIdFromResponse(response)
        } finally {
            runCatching { response.close() }
        }
    }

    /**
     * The one typed failure site for a decoded (non-transport) discovery reply:
     * a non-2xx status or a body whose `data[]` carries no `gpt://` entry.
     */
    private fun folderIdFromResponse(response: Response): String {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val detail = detailFromBody(raw)
            val suffix = if (detail.isNullOrBlank()) "" else " ($detail)"
            throw YandexFolderResolutionException(
                "Yandex model discovery failed (HTTP ${response.code})$suffix"
            )
        }
        return parseFolderId(raw)
            ?: throw YandexFolderResolutionException("Yandex model discovery returned no gpt:// entry")
    }

    /**
     * Extracts the folder from the first `gpt://<folder>/...` model id.
     * Malformed or empty `data[]` yields null (the caller raises the typed
     * error) — never an exception from the JSON layer.
     */
    private fun parseFolderId(raw: String): String? {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val data = root["data"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        return data.firstNotNullOfOrNull { folderFromEntry(it) }
    }

    /** Folder = the first path segment of a `gpt://<folder>/…` model id. */
    private fun folderFromEntry(entry: JsonElement): String? {
        val id = runCatching { entry.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            ?: return null
        if (!id.startsWith(GPT_SCHEME)) return null
        return id.removePrefix(GPT_SCHEME).split('/').firstOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun extractOutputText(parsed: YandexResponse): String {
        parsed.outputText?.takeIf { it.isNotBlank() }?.let { return it }
        return parsed.output.asSequence()
            .flatMap { it.content.asSequence() }
            .firstOrNull { it.type == TYPE_OUTPUT_TEXT && !it.text.isNullOrBlank() }
            ?.text
            .orEmpty()
    }

    /** RFC-7807 `detail` (or `title`/in-stream `message`) from an error body. */
    private fun detailFromBody(raw: String): String? {
        if (raw.isBlank()) return null
        val error = runCatching { json.decodeFromString(YandexError.serializer(), raw) }.getOrNull()
        return error?.detail ?: error?.message ?: error?.title
    }

    private companion object {
        const val GPT_SCHEME = "gpt://"
        const val RESPONSES_PATH = "responses"
        const val MODELS_PATH = "models"
        const val AUTHORIZATION = "Authorization"
        const val EVENT_STREAM = "text/event-stream"
        const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        const val TYPE_OUTPUT_TEXT = "output_text"

        /**
         * Dedicated bound for `GET /models` folder discovery (5 s). Explicit
         * per-discovery value: the shared OkHttp client's defaults are not a
         * contract for this call.
         */
        const val DEFAULT_FOLDER_DISCOVERY_TIMEOUT_MS = 5_000L
    }
}

/**
 * The service account folder could not be resolved (discovery non-2xx, empty
 * or malformed `data[]`, or a transport timeout). A fatal, typed failure —
 * never a crash and never a silently empty answer.
 */
class YandexFolderResolutionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A 2xx response whose envelope could not be decoded. Deliberately an
 * [IOException] so the Cognitive extraction lane classifies it as a
 * transport/protocol anomaly (release the batch and retry) instead of an
 * empty completion that would quarantine every row in the batch.
 */
class LlmResponseParseException(message: String) : IOException(message)
