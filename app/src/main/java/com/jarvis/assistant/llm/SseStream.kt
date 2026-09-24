package com.jarvis.assistant.llm

import kotlinx.coroutines.ensureActive
import okio.BufferedSource
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Reusable, cancellation-correct SSE transport read loop.
 *
 * Extracted from [SseLlmClient] so the native GigaChat stream (which carries
 * semantics on `event:` lines) shares the exact barge-in guarantees:
 *
 * - The blocking `readUtf8Line()` runs in the caller's IO coroutine; when the
 *   collector cancels, `awaitClose { call.cancel() }` closes the socket and the
 *   blocked read unblocks with an [IOException]. Passing [isCancelled] lets the
 *   loop distinguish that benign case (return silently) from a real transport
 *   failure (rethrow — the caller closes the channel with the cause).
 * - [coroutineContext].ensureActive() stops consuming as soon as the job is
 *   cancelled, even if a byte trickles in.
 *
 * The assembler captures BOTH `data:` payloads and the `event:` name, because
 * the native protocol encodes required meaning in the event name. Events are
 * dispatched on the terminating blank line (multi-line `data:` joined with
 * `\n` per spec); [Assembler.flush] emits a pending unterminated event at EOF.
 */
object SseStream {

    /** One completed SSE event: the optional `event:` name plus its data. */
    data class Event(val name: String?, val data: String)

    /**
     * Reads events until EOF, cancellation, or [onEvent] returns `true`
     * (the `[DONE]` / terminal-event short-circuit). Never closes the source —
     * the caller owns the response-body lifecycle.
     */
    suspend fun read(
        source: BufferedSource,
        isCancelled: () -> Boolean,
        onEvent: suspend (Event) -> Boolean,
    ) {
        val assembler = Assembler()
        while (true) {
            coroutineContext.ensureActive()
            val line = try {
                source.readUtf8Line()
            } catch (e: IOException) {
                if (isCancelled()) return // barge-in: stop silently
                throw e
            }
            if (line == null) break // EOF
            val event = assembler.offer(line)
            if (event != null && onEvent(event)) return
        }
        // EOF tolerance: a server that omits the final blank line still
        // delivers its last event.
        assembler.flush()?.let { onEvent(it) }
    }

    /**
     * Field accumulator for one event. Unlike [SseParser.EventAssembler] this
     * keeps the `event:` name; an event with no `data:` lines is never
     * dispatched (a bare keep-alive `event:` line is meaningless).
     */
    class Assembler {
        private val dataLines = ArrayList<String>(2)
        private var eventName: String? = null

        fun offer(line: String): Event? {
            if (line.isBlank()) return flush()
            if (line.startsWith(":")) return null // comment
            if (line.startsWith("event:")) {
                eventName = line.removePrefix("event:").trim()
                return null
            }
            if (!line.startsWith("data:")) return null // id:, retry:, ...
            dataLines += line.removePrefix("data:").trim()
            return null
        }

        fun flush(): Event? {
            if (dataLines.isEmpty()) {
                eventName = null
                return null
            }
            val event = Event(eventName, dataLines.joinToString("\n"))
            dataLines.clear()
            eventName = null
            return event
        }
    }
}
