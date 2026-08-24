package com.jarvis.assistant.api

import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.LlmChunk
import com.jarvis.assistant.contracts.LlmClient
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.Tool
import com.jarvis.assistant.contracts.ToolCall
import com.jarvis.assistant.contracts.TokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * GigaChat LLM client (OpenAI-compatible streaming chat completions).
 *
 * Streams Server-Sent Events from the GigaChat chat completions endpoint and
 * converts them into [LlmChunk]s.
 *
 * ## SSE tool-call accumulation (critical)
 * GigaChat emits `tool_calls[].function.arguments` as **incremental** string
 * deltas across multiple chunks (one index per parallel tool call). We therefore
 * keep a per-index accumulator ([ToolCallAccum]) and:
 *   - emit [LlmChunk.FunctionCallDelta] for every delta (so streaming consumers
 *     can show progress), and
 *   - on stream end ([DONE] or a non-null `finish_reason`) emit exactly one
 *     [LlmChunk.FunctionCallComplete] per index, with the **concatenated**
 *     arguments string and the captured function name.
 * Text deltas are emitted immediately as [LlmChunk.Text].
 */
class GigaChatClient(private val tokenProvider: TokenProvider) : LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val endpoint = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions"

    override fun chatStream(
        messages: List<Message>,
        tools: List<Tool>
    ): Flow<LlmChunk> = callbackFlow {
        var call: Call? = null

        withContext(Dispatchers.IO) {
            val token = tokenProvider.getGigaChatToken()

            val requestJson = JsonObject(
                mapOf(
                    "model" to JsonPrimitive("GigaChat-Pro"),
                    "messages" to json.encodeToJsonElement(ListSerializer(Message.serializer()), messages),
                    "tools" to toolsToJson(tools),
                    "tool_choice" to JsonPrimitive("auto"),
                    "stream" to JsonPrimitive(true),
                    "temperature" to JsonPrimitive(0.7),
                    "max_tokens" to JsonPrimitive(2048)
                )
            )

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = json.encodeToString(JsonElement.serializer(), requestJson)
                .toRequestBody(mediaType)

            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "text/event-stream")
                .post(body)
                .build()

            val newCall = client.newCall(request)
            call = newCall
            val response = newCall.execute()

            if (!response.isSuccessful) {
                val err = response.body?.string().orEmpty()
                response.close()
                Timber.d("GigaChat request failed: HTTP ${response.code}: $err")
                throw RuntimeException(
                    "GigaChat request failed (HTTP ${response.code}): $err"
                )
            }

            val bodySource = response.body?.source()
                ?: throw RuntimeException("GigaChat returned an empty body")

            val acc = mutableMapOf<Int, ToolCallAccum>()
            var toolCallsFinalized = false

            fun finalizeToolCalls() {
                if (toolCallsFinalized) return
                toolCallsFinalized = true
                acc.toSortedMap().forEach { (_, a) ->
                    val name = a.name ?: "unknown"
                    val id = a.id ?: java.util.UUID.randomUUID().toString()
                    trySend(
                        LlmChunk.FunctionCallComplete(
                            ToolCall(
                                id = id,
                                function = FunctionCall(name, a.args.toString())
                            )
                        )
                    )
                }
            }

            // ---- Manual SSE parsing (FIX #4) ----
            while (!isClosedForSend) {
                val line = bodySource.readUtf8Line() ?: break // EOF

                // Skip blank lines and SSE comments (lines starting with ':').
                if (line.isBlank()) continue
                if (line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue

                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    finalizeToolCalls()
                    trySend(LlmChunk.Done)
                    break
                }

                val chunk = parseChunk(data) ?: continue // skip malformed chunks

                val choices = chunk["choices"]?.jsonArray
                if (choices.isNullOrEmpty()) continue
                val choice = choices[0].jsonObject
                val delta = choice["delta"]?.jsonObject ?: continue

                // Text delta.
                val text = delta["content"]?.jsonPrimitive?.contentOrNull
                if (!text.isNullOrEmpty()) {
                    trySend(LlmChunk.Text(text))
                }

                // Tool-call deltas (incremental, keyed by index).
                val toolCalls = delta["tool_calls"]?.jsonArray
                if (toolCalls != null) {
                    for (tc in toolCalls) {
                        val tco = tc.jsonObject
                        val index = tco["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                        val tcoId = tco["id"]?.jsonPrimitive?.contentOrNull
                        val fn = tco["function"]?.jsonObject
                        val name = fn?.get("name")?.jsonPrimitive?.contentOrNull
                        val argsDelta = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""

                        val a = acc.getOrPut(index) {
                            ToolCallAccum(index, null, StringBuilder(), null)
                        }
                        if (tcoId != null) a.id = tcoId
                        if (name != null) a.name = name
                        a.args.append(argsDelta)

                        trySend(LlmChunk.FunctionCallDelta(index, name, argsDelta))
                    }
                }

                // A finish reason (e.g. "stop" / "tool_calls") means the model is
                // done producing this choice; finalize tool calls now. [DONE]
                // still drives the terminal Done chunk.
                val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (!finishReason.isNullOrBlank()) {
                    finalizeToolCalls()
                }
            }
        }

        awaitClose { call?.cancel() }
    }

    /**
     * GigaChat expects `tools[].function.parameters` to be a JSON **object**,
     * but the contract models it as a raw JSON **string**. This helper parses
     * each `parameters` string back into a JsonElement so the wire request is
     * valid; unparseable values fall back to an empty object.
     */
    private fun toolsToJson(tools: List<Tool>): JsonArray {
        val arr = json.encodeToJsonElement(ListSerializer(Tool.serializer()), tools).jsonArray
        return JsonArray(
            arr.map { toolEl ->
                val obj = toolEl.jsonObject
                val fn = obj["function"]?.jsonObject ?: JsonObject(emptyMap())
                val paramsStr = fn["parameters"]?.jsonPrimitive?.contentOrNull
                val paramsEl = try {
                    if (paramsStr != null) {
                        json.parseToJsonElement(paramsStr)
                    } else {
                        JsonObject(emptyMap())
                    }
                } catch (e: Exception) {
                    JsonObject(emptyMap())
                }
                val newFn = JsonObject(fn.toMap() + ("parameters" to paramsEl))
                JsonObject(obj.toMap() + ("function" to newFn))
            }
        )
    }

    private fun parseChunk(data: String): JsonObject? = try {
        json.parseToJsonElement(data).jsonObject
    } catch (e: Exception) {
        null // bad chunk -> skip, never crash the stream
    }

    private data class ToolCallAccum(
        val index: Int,
        var name: String?,
        val args: StringBuilder,
        var id: String?
    )
}