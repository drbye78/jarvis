package com.jarvis.assistant

import com.jarvis.assistant.llm.SseStream
import com.jarvis.assistant.llm.YandexResponseFailedException
import com.jarvis.assistant.llm.YandexSseParser
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the LIVE-CAPTURED Yandex AI Studio streams (`recorded/yandex/…sse`)
 * through the Responses parser.
 *
 * The load-bearing assertions are LANE SEPARATION (web_search progress and
 * url_citation annotations must never surface as spoken text) and the
 * exactly-once Done with no `[DONE]` sentinel — the stream simply ends after
 * `response.completed`.
 */
class YandexSseParserTest {

    private fun replay(resource: String, parser: YandexSseParser): List<LlmChunk> = runBlocking {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) {
            "missing fixture $resource"
        }.bufferedReader().use { it.readText() }
        val source = Buffer().apply { writeUtf8(text) }
        val out = ArrayList<LlmChunk>()
        SseStream.read(source, isCancelled = { false }) { event ->
            out += parser.parse(event.data)
            false
        }
        out += parser.finish()
        out
    }

    private fun textOf(chunks: List<LlmChunk>): String =
        chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text }

    @Test
    fun `plain stream concatenates incremental deltas with exactly one done`() {
        val parser = YandexSseParser(advertisedToolNames = setOf("get_weather"))
        val chunks = replay("recorded/yandex/plain.sse", parser)

        assertEquals("Привет, мир!", textOf(chunks))
        assertEquals("exactly one Done", 1, chunks.count { it is LlmChunk.Done })
        assertEquals(0, chunks.count { it is LlmChunk.FunctionCallComplete })
    }

    @Test
    fun `search stream speaks text but never the search query or citations`() {
        val parser = YandexSseParser(advertisedToolNames = setOf("get_weather"))
        val chunks = replay("recorded/yandex/search.sse", parser)
        val text = textOf(chunks)

        assertTrue("search answer must be spoken", text.contains("пасмурно"))
        assertEquals("exactly one Done", 1, chunks.count { it is LlmChunk.Done })
        assertEquals(0, chunks.count { it is LlmChunk.FunctionCallComplete })

        // The web_search action query is telemetry, not speech.
        assertFalse("search query must not be spoken", text.contains("погода в Москве сейчас"))
        // url_citation annotations (the citation cards) must never be spoken.
        assertFalse("citation url must not be spoken", text.contains("pogoda.mail.ru"))
        assertFalse("citation url must not be spoken", text.contains("2gis.ru"))
        assertFalse("citation url must not be spoken", text.contains("world-weather.ru"))
        assertFalse("citation url must not be spoken", text.contains("gismeteo.ru"))
        assertFalse("annotation type must not be spoken", text.contains("url_citation"))
        assertFalse("output_item type must not be spoken", text.contains("web_search_call"))
    }

    @Test
    fun `function call is emitted exactly once with its call_id preserved`() {
        val parser = YandexSseParser(advertisedToolNames = setOf("get_weather"))
        val chunks = replay("recorded/yandex/func.sse", parser)

        val calls = chunks.filterIsInstance<LlmChunk.FunctionCallComplete>()
        assertEquals("arguments delta and done must not double-emit", 1, calls.size)
        val call = calls.single()
        assertEquals("get_weather", call.call.function.name)
        assertEquals("call_id must be preserved verbatim", "get_weather", call.call.id)
        assertEquals("""{"city":"Казань"}""", call.call.function.arguments)
        assertEquals(1, chunks.count { it is LlmChunk.Done })
        assertEquals("a pure function-call turn has no spoken text", "", textOf(chunks))
    }

    @Test
    fun `unadvertised function call is suppressed`() {
        val parser = YandexSseParser(advertisedToolNames = emptySet())
        val chunks = replay("recorded/yandex/func.sse", parser)

        assertEquals(0, chunks.count { it is LlmChunk.FunctionCallComplete })
        assertEquals("the turn still terminates", 1, chunks.count { it is LlmChunk.Done })
    }

    @Test
    fun `completed frame latches done and a repeat is ignored`() {
        val parser = YandexSseParser(advertisedToolNames = emptySet())
        assertTrue(parser.parse("""{"type":"response.completed"}""").any { it is LlmChunk.Done })
        assertTrue(
            "a second completed frame must not re-emit",
            parser.parse("""{"type":"response.completed"}""").isEmpty(),
        )
        assertTrue("EOF finalizer must not re-emit", parser.finish().isEmpty())
    }

    @Test
    fun `failed frame raises a typed terminal failure`() {
        val parser = YandexSseParser(advertisedToolNames = emptySet())
        val frame = "{\"type\":\"response.failed\",\"response\":{\"status\":\"failed\"," +
            "\"error\":{\"code\":\"server_error\",\"message\":\"boom\"}}}"
        val error = runCatching { parser.parse(frame) }.exceptionOrNull()
        assertTrue("expected a typed failure, got $error", error is YandexResponseFailedException)
        assertTrue("failure must carry the detail", error!!.message!!.contains("boom"))
    }
}
