package com.jarvis.assistant.llm

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.wire.GigaChatChatResponse
import com.jarvis.assistant.wire.GigaChatFunctionCall
import com.jarvis.assistant.wire.GigaChatResponsePart
import com.jarvis.assistant.wire.GigaChatSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.util.UUID

/**
 * Parser for the GigaChat **native** (`/v2`) stream — named `event:` SSE
 * events whose payloads are [GigaChatChatResponse] envelopes.
 *
 * Lane separation is the whole point:
 * - assistant `text` parts → [LlmChunk.Text];
 * - client `function_call` parts → [LlmChunk.FunctionCallComplete], but ONLY
 *   for names we advertised (a server-invented call must never reach the tool
 *   router);
 * - `tool_execution` progress (web_search in_progress/completed) is dropped —
 *   it is telemetry, never speech;
 * - `inline_data.sources` are retained on the [sources] side channel for a
 *   future UI card and NEVER enter the text stream, so search citations are
 *   not read aloud.
 *
 * The class is stateful only for the Done latch (exactly one [LlmChunk.Done]
 * per stream) and the sources accumulator; every field parse is pure and
 * JVM-testable. Text deltas are incremental (live-verified) — concatenate,
 * no de-duplication.
 */
class GigaChatSseParser(
    private val advertisedToolNames: Set<String>,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val sourcesById = LinkedHashMap<String, GigaChatSource>()
    private var doneEmitted = false

    /** Sources seen so far, for later UI use — never part of the text lane. */
    val sources: List<GigaChatSource> get() = sourcesById.values.toList()

    /**
     * Parses one `(event, data)` pair. Returns the chunks it contributes
     * (possibly empty for suppressed events) and stops contributing after the
     * first Done.
     */
    fun parse(eventName: String?, data: String): List<LlmChunk> {
        if (doneEmitted) return emptyList()
        if (data == DONE_MARKER) return emitDone()
        val response = decode(data) ?: return emptyList()
        val out = ArrayList<LlmChunk>(2)
        for (message in response.messages) {
            for (part in message.content) {
                appendPart(part, out)
            }
        }
        if (eventName == EVENT_MESSAGE_DONE || response.finishReason != null) {
            out += emitDone()
        }
        return out
    }

    /**
     * EOF finalizer: guarantees exactly one Done even when the stream ends
     * without a `response.message.done` event or a `[DONE]` sentinel.
     */
    fun finish(): List<LlmChunk> = emitDone()

    private fun appendPart(part: GigaChatResponsePart, out: MutableList<LlmChunk>) {
        part.text?.takeIf { it.isNotEmpty() }?.let { out += LlmChunk.Text(it) }
        appendFunctionCall(part.functionCall, out)
        // tool_execution is intentionally dropped: it is web_search progress,
        // not assistant output.
        part.inlineData?.sources?.let { sourcesById.putAll(it) }
    }

    private fun appendFunctionCall(call: GigaChatFunctionCall?, out: MutableList<LlmChunk>) {
        if (call == null) return
        val name = call.name ?: return
        if (name !in advertisedToolNames) return
        out += LlmChunk.FunctionCallComplete(
            ToolCall(
                id = call.id ?: UUID.randomUUID().toString(),
                function = FunctionCall(name, encodeArguments(call.arguments)),
            )
        )
    }

    private fun encodeArguments(arguments: JsonElement?): String =
        if (arguments == null) {
            EMPTY_OBJECT
        } else {
            json.encodeToString(JsonElement.serializer(), arguments)
        }

    private fun decode(data: String): GigaChatChatResponse? =
        runCatching { json.decodeFromString(GigaChatChatResponse.serializer(), data) }.getOrNull()

    private fun emitDone(): List<LlmChunk> {
        if (doneEmitted) return emptyList()
        doneEmitted = true
        return listOf(LlmChunk.Done)
    }

    companion object {
        const val EVENT_MESSAGE_DONE = "response.message.done"
        const val DONE_MARKER = "[DONE]"
        private const val EMPTY_OBJECT = "{}"
    }
}
