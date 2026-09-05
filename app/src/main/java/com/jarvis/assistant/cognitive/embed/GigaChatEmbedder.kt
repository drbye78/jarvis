package com.jarvis.assistant.cognitive.embed

import com.jarvis.assistant.llm.LlmHttpException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import java.io.IOException

/**
 * COGNITIVE_PLAN Phase 3 (§11): the GigaChat embeddings engine — the CLOUD
 * branch of the `memory.embedder` selector (§12.4-3).
 *
 * Endpoint: OpenAI-compatible `POST {base}/api/v1/embeddings` with the SAME
 * OAuth bearer token the chat transport uses ([TokenManager]); the model is
 * GigaChat's `Embeddings` (1024-dim). Non-2xx replies raise
 * [LlmHttpException] with the code — callers classify fatal (4xx, e.g. the
 * account has no embeddings entitlement) vs transient (429/5xx) exactly
 * like the extraction pipeline.
 *
 * Transport seam: [postJson] is an injected `suspend (url, body) -> reply`
 * so JVM tests fake the endpoint entirely; [gigaChatHttpTransport] is the
 * production adapter (OkHttp + token). The seam also keeps fact VALUES out
 * of any test log — no egress happens in CI, ever.
 *
 * Privacy (§9.2 truth table): using this engine SENDS FACT VALUES AND
 * QUERIES to GigaChat — the Settings card discloses this before backfill
 * with this engine, and the on-device benchmark uses only STATIC SYNTHETIC
 * probe strings (never user facts), so «Проверить качество» needs no
 * privacy dialog even on the cloud branch.
 */
class GigaChatEmbedder(
    private val embeddingsEndpoint: String,
    private val postJson: suspend (url: String, bodyJson: String) -> TransportReply,
    override val dim: Int = DIM,
) : EmbeddingEngine {

    override val engineId: String = EmbeddingEngine.CLOUD_ID
    override val kind: EmbeddingEngine.Kind = EmbeddingEngine.Kind.CLOUD

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val body = buildJsonObject {
            put("model", MODEL)
            put("input", JsonArray(texts.map { JsonPrimitive(it) }))
        }
        val reply = postJson(embeddingsEndpoint, body.toString())
        if (reply.code != 200) throw LlmHttpException(reply.code)
        val root = reply.body?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
            ?: throw IOException("embeddings reply is not valid JSON")
        val data = (root as? JsonObject)?.get("data")?.jsonArray
            ?: throw IOException("embeddings reply has no data array")
        if (data.size != texts.size) {
            throw IOException("embeddings size ${data.size} != requested ${texts.size}")
        }
        // Honor the index field — order the reply into the request order.
        val out = arrayOfNulls<FloatArray>(texts.size)
        for (element in data) {
            val obj = element.jsonObject
            val index = obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: out.indexOfFirst { it == null }
            require(index in out.indices) { "embeddings index out of range: $index" }
            require(out[index] == null) { "duplicate embeddings index: $index" }
            val vec = obj["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }
                ?.toFloatArray()
                ?: throw IOException("embedding entry without an array")
            if (vec.size != dim) throw IOException("embedding dim ${vec.size} != $dim")
            out[index] = VectorMath.l2Normalize(vec)
        }
        return out.map { requireNotNull(it) { "missing embedding at index" } }
    }

    /**
     * §12.4-3 entitlement check: a single synthetic-string probe. 4xx → the
     * account cannot use embeddings (NOT_ENTITLED); transient failures are
     * surfaced as [EmbeddingEngine.Entitlement.Transient] so the UI can say
     * "network/service problem" instead of wrongly claiming a verdict.
     */
    override suspend fun checkEntitlement(): EmbeddingEngine.Entitlement = try {
        embed(listOf(PROBE_TEXT))
        EmbeddingEngine.Entitlement.Ok
    } catch (e: LlmHttpException) {
        if (e.isTransient) {
            EmbeddingEngine.Entitlement.Transient(e.code)
        } else {
            EmbeddingEngine.Entitlement.Denied(e.code)
        }
    } catch (_: IOException) {
        // Transport problem — no entitlement verdict, retry later.
        EmbeddingEngine.Entitlement.Transient(-1)
    }

    /** Reply envelope of the injected transport. */
    class TransportReply(val code: Int, val body: String?)

    companion object {
        const val MODEL = "Embeddings"
        const val DIM = 1024

        /** Synthetic probe text — never user data (see privacy note). */
        const val PROBE_TEXT = "проверка доступности сервиса векторизации"

        /**
         * Production transport: Bearer token per call ([tokenProvider] is
         * suspend — OAuth may refresh), JSON POST, bounded error body.
         * IOExceptions propagate (transient by definition).
         */
        fun gigaChatHttpTransport(
            httpClient: OkHttpClient,
            tokenProvider: suspend () -> String,
        ): suspend (String, String) -> TransportReply = { url, bodyJson ->
            val token = tokenProvider()
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = httpClient.newCall(request).execute()
            response.use {
                if (it.isSuccessful) {
                    TransportReply(it.code, it.body?.string())
                } else {
                    val err = runCatching { it.body?.string() }.getOrNull().orEmpty()
                    TransportReply(it.code, err.take(512))
                }
            }
        }

        /** Derives the embeddings URL from the configured chat endpoint. */
        fun endpointFor(chatEndpoint: String): String =
            chatEndpoint.removeSuffix("/chat/completions") + "/embeddings"
    }
}
