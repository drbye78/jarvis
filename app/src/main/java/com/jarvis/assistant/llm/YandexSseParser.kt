package com.jarvis.assistant.llm

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.wire.YandexStreamEvent
import kotlinx.serialization.json.Json

/**
 * Parser for the Yandex AI Studio Responses API SSE stream.
 *
 * Dispatch is on the JSON `data.type`; the named `event:` line duplicates it
 * and is ignored. Lane separation:
 * - `response.output_text.delta` → [LlmChunk.Text] (incremental concatenation,
 *   no de-duplication — live-verified);
 * - `response.output_item.done` with `item.type == "function_call"` → exactly
 *   one [LlmChunk.FunctionCallComplete], but ONLY for a name we advertised;
 * - `response.completed` → one [LlmChunk.Done] (latched; [finish] supplies the
 *   EOF fallback — there is NO `[DONE]` sentinel);
 * - `response.failed` / `response.incomplete` → a typed
 *   [YandexResponseFailedException], so the session lane treats it as a fatal
 *   turn failure rather than speaking a partial answer;
 * - web_search progress, annotations/citations, reasoning and lifecycle frames
 *   are ignored — none of them may reach the spoken text lane.
 *
 * `call_id` is preserved verbatim on the [ToolCall]: it is the pairing key for
 * the follow-up `function_call_output` item, so regenerating it would break
 * the tool round-trip.
 */
class YandexSseParser(
    private val advertisedToolNames: Set<String>,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var doneEmitted = false

    /**
     * Parses one SSE `data:` payload. Returns the chunks it contributes
     * (empty for ignored frames) and stops contributing after the first Done.
     * Throws [YandexResponseFailedException] on a terminal failure frame.
     */
    fun parse(data: String): List<LlmChunk> {
        if (doneEmitted) return emptyList()
        val event = decode(data) ?: return emptyList()
        return when (event.type) {
            TYPE_OUTPUT_TEXT_DELTA -> textDelta(event)
            TYPE_OUTPUT_ITEM_DONE -> itemDone(event)
            TYPE_COMPLETED -> emitDone()
            TYPE_FAILED, TYPE_INCOMPLETE -> throw failure(event)
            else -> emptyList()
        }
    }

    /**
     * EOF finalizer: guarantees exactly one Done even though the stream ends
     * without a `[DONE]` sentinel.
     */
    fun finish(): List<LlmChunk> = emitDone()

    private fun textDelta(event: YandexStreamEvent): List<LlmChunk> {
        val delta = event.delta?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return listOf(LlmChunk.Text(delta))
    }

    private fun itemDone(event: YandexStreamEvent): List<LlmChunk> {
        val item = event.item
        if (item?.type != TYPE_FUNCTION_CALL) return emptyList()
        val name = item.name
        // Preserve call_id; fall back to the item id rather than fabricating
        // one (a regenerated id would orphan the function_call_output).
        val callId = item.callId ?: item.id
        if (name == null || name !in advertisedToolNames || callId == null) return emptyList()
        return listOf(
            LlmChunk.FunctionCallComplete(
                ToolCall(
                    id = callId,
                    function = FunctionCall(name, item.arguments ?: EMPTY_OBJECT),
                )
            )
        )
    }

    private fun failure(event: YandexStreamEvent): YandexResponseFailedException {
        val error = event.response?.error
        val detail = error?.message ?: error?.detail ?: error?.code
        val message = if (detail.isNullOrBlank()) {
            "Yandex response ${event.type}"
        } else {
            "Yandex response ${event.type}: $detail"
        }
        return YandexResponseFailedException(message)
    }

    private fun decode(data: String): YandexStreamEvent? =
        runCatching { json.decodeFromString(YandexStreamEvent.serializer(), data) }.getOrNull()

    private fun emitDone(): List<LlmChunk> {
        if (doneEmitted) return emptyList()
        doneEmitted = true
        return listOf(LlmChunk.Done)
    }

    companion object {
        const val TYPE_OUTPUT_TEXT_DELTA = "response.output_text.delta"
        const val TYPE_OUTPUT_ITEM_DONE = "response.output_item.done"
        const val TYPE_COMPLETED = "response.completed"
        const val TYPE_FAILED = "response.failed"
        const val TYPE_INCOMPLETE = "response.incomplete"
        private const val TYPE_FUNCTION_CALL = "function_call"
        private const val EMPTY_OBJECT = "{}"
    }
}

/** A terminal `response.failed` / `response.incomplete` frame. */
class YandexResponseFailedException(message: String) : RuntimeException(message)
