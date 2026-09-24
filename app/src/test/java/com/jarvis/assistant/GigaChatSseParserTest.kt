package com.jarvis.assistant

import com.jarvis.assistant.llm.GigaChatSseParser
import com.jarvis.assistant.llm.SseStream
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the LIVE-CAPTURED GigaChat /v2 streams
 * (`recorded/gigachat/…sse` fixtures) through the native parser.
 *
 * The critical assertions are about LANE SEPARATION: `tool_execution`
 * progress and `inline_data.sources` must never surface as spoken text, while
 * `response.message.done` (and any `[DONE]` sentinel) yields exactly one Done.
 */
class GigaChatSseParserTest {

    private fun replay(resource: String, parser: GigaChatSseParser): List<LlmChunk> = runBlocking {
        val text = requireNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) {
            "missing fixture $resource"
        }.bufferedReader().use { it.readText() }
        val source = Buffer().apply { writeUtf8(text) }
        val out = ArrayList<LlmChunk>()
        SseStream.read(source, isCancelled = { false }) { event ->
            out += parser.parse(event.name, event.data)
            false
        }
        out += parser.finish()
        out
    }

    private fun textOf(chunks: List<LlmChunk>): String =
        chunks.filterIsInstance<LlmChunk.Text>().joinToString("") { it.text }

    @Test
    fun `plain stream concatenates its single text delta`() {
        val parser = GigaChatSseParser(advertisedToolNames = setOf("getWeather"))
        val chunks = replay("recorded/gigachat/plain.sse", parser)

        assertEquals("Да", textOf(chunks))
        assertEquals(1, chunks.count { it is LlmChunk.Done })
        assertEquals("no tool call in a plain turn", 0, chunks.count { it is LlmChunk.FunctionCallComplete })
    }

    @Test
    fun `search stream speaks text but never tool progress or source urls`() {
        val parser = GigaChatSseParser(advertisedToolNames = setOf("getWeather"))
        val chunks = replay("recorded/gigachat/search.sse", parser)
        val text = textOf(chunks)

        assertTrue("search answer must be spoken", text.contains("84,3969"))
        assertFalse("web_search progress must not enter the text lane", text.contains("web_search"))
        assertFalse("tool_execution must not enter the text lane", text.contains("tool_execution"))
        assertFalse("source URLs must not be spoken", text.contains("https://"))
        assertFalse("source titles must not be spoken", text.contains("Финмаркет"))
        assertEquals(1, chunks.count { it is LlmChunk.Done })
        assertEquals(
            "a web-search turn has no pending tool calls",
            0,
            chunks.count { it is LlmChunk.FunctionCallComplete },
        )
    }

    @Test
    fun `sources are retained on the side channel only`() {
        val parser = GigaChatSseParser(advertisedToolNames = emptySet())
        replay("recorded/gigachat/search.sse", parser)

        assertEquals(5, parser.sources.size)
        assertTrue(parser.sources.all { it.url?.startsWith("https://") == true })
    }

    @Test
    fun `client function call is emitted only for an advertised name`() {
        val funcJson = requireNotNull(
            javaClass.classLoader!!.getResourceAsStream("recorded/gigachat/func.json")
        ) { "missing fixture func.json" }.bufferedReader().use { it.readText() }

        val advertised = GigaChatSseParser(advertisedToolNames = setOf("getWeather"))
        val chunks = advertised.parse("response.message.delta", funcJson)
        val call = chunks.filterIsInstance<LlmChunk.FunctionCallComplete>().single()
        assertEquals("getWeather", call.call.function.name)
        assertTrue(
            "arguments must round-trip as an object's JSON string",
            call.call.function.arguments.contains("Москва"),
        )
        assertTrue("function_call finish must terminate with Done", chunks.any { it is LlmChunk.Done })

        val unadvertised = GigaChatSseParser(advertisedToolNames = emptySet())
        val filtered = unadvertised.parse("response.message.delta", funcJson)
        assertTrue("unadvertised calls are suppressed", filtered.none { it is LlmChunk.FunctionCallComplete })
    }

    @Test
    fun `done sentinel after a done event does not double emit`() {
        val parser = GigaChatSseParser(advertisedToolNames = emptySet())
        assertTrue(
            parser.parse(GigaChatSseParser.EVENT_MESSAGE_DONE, """{"finish_reason":"stop"}""")
                .any { it is LlmChunk.Done },
        )
        assertTrue(parser.parse(null, GigaChatSseParser.DONE_MARKER).isEmpty())
        assertTrue(parser.finish().isEmpty())
    }
}
