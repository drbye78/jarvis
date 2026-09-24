package com.jarvis.assistant

import com.jarvis.assistant.llm.SseStream
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Pins the extracted SSE transport ([SseStream]): `event:` name capture,
 * `[DONE]` passthrough, EOF flush, and the barge-in rule that a cancelled
 * call's [IOException] is swallowed while a genuine transport failure is
 * rethrown (the caller closes the channel with the cause).
 */
class SseStreamTest {

    private fun collect(body: String, stopAfter: Int = Int.MAX_VALUE): List<SseStream.Event> =
        runBlocking {
            val out = ArrayList<SseStream.Event>()
            SseStream.read(Buffer().apply { writeUtf8(body) }, isCancelled = { false }) { event ->
                out += event
                out.size >= stopAfter
            }
            out
        }

    @Test
    fun `named event captures both name and data`() {
        val events = collect("event: response.message.delta\ndata: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertEquals("response.message.delta", events.single().name)
        assertEquals("{\"a\":1}", events.single().data)
    }

    @Test
    fun `data-only event keeps a null name for the openai-compat lane`() {
        val events = collect("data: {\"choices\":[]}\n\n")
        assertEquals(1, events.size)
        assertNull(events.single().name)
        assertEquals("{\"choices\":[]}", events.single().data)
    }

    @Test
    fun `done sentinel is delivered as an event`() {
        val events = collect("data: [DONE]\n\n")
        assertEquals(1, events.size)
        assertNull(events.single().name)
        assertEquals("[DONE]", events.single().data)
    }

    @Test
    fun `unterminated final event is flushed at eof`() {
        val events = collect("event: tail\ndata: last")
        assertEquals(1, events.size)
        assertEquals("tail", events.single().name)
        assertEquals("last", events.single().data)
    }

    @Test
    fun `multi-line data is joined per the sse spec`() {
        val events = collect("data: first\ndata: second\n\n")
        assertEquals("first\nsecond", events.single().data)
    }

    @Test
    fun `returning true stops the read`() {
        val events = collect("data: one\n\ndata: two\n\ndata: three\n\n", stopAfter = 2)
        assertEquals(listOf("one", "two"), events.map { it.data })
    }

    @Test
    fun `cancelled transport failure is swallowed`() {
        val events = runBlocking {
            val out = ArrayList<SseStream.Event>()
            SseStream.read(failingSource(), isCancelled = { true }) { event ->
                out += event
                false
            }
            out
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `genuine transport failure is rethrown`() {
        try {
            runBlocking { SseStream.read(failingSource(), isCancelled = { false }) { false } }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
    }

    private fun failingSource(): BufferedSource =
        object : ForwardingSource(Buffer()) {
            override fun read(sink: Buffer, byteCount: Long): Long = throw IOException("boom")
        }.buffer()
}
