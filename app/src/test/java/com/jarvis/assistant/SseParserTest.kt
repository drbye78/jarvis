package com.jarvis.assistant

import com.jarvis.assistant.llm.SseParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `data payload extraction`() {
        assertEquals("""{"a":1}""", SseParser.dataPayload("""data: {"a":1}"""))
        assertEquals("""{"a":1}""", SseParser.dataPayload("""data:{"a":1}"""))
        assertNull(SseParser.dataPayload(""))
        assertNull(SseParser.dataPayload(": keep-alive comment"))
        assertNull(SseParser.dataPayload("event: message"))
    }

    @Test
    fun `done marker`() {
        assertTrue(SseParser.isDone("[DONE]"))
        assertTrue(!SseParser.isDone("""{"x":1}"""))
    }

    @Test
    fun `text delta`() {
        val payload = """{"choices":[{"delta":{"content":"Привет"},"index":0}]}"""
        val parsed = SseParser.parseChunk(json, payload)
        assertNotNull(parsed)
        assertEquals("Привет", parsed!!.text)
        assertTrue(parsed.toolDeltas.isEmpty())
        assertNull(parsed.finishReason)
    }

    @Test
    fun `tool call delta with name and id`() {
        val payload = """
            {"choices":[{"delta":{"tool_calls":[
                {"index":0,"id":"call_1","function":{"name":"setAlarm","arguments":"{\"tim"}}
            ]},"index":0}]}
        """.trimIndent()
        val parsed = SseParser.parseChunk(json, payload)
        assertNotNull(parsed)
        val d = parsed!!.toolDeltas.single()
        assertEquals(0, d.index)
        assertEquals("call_1", d.id)
        assertEquals("setAlarm", d.name)
        assertEquals("""{"tim""", d.argsDelta)
    }

    @Test
    fun `tool call argument continuation delta`() {
        val payload = """
            {"choices":[{"delta":{"tool_calls":[
                {"index":0,"function":{"arguments":"e\":\"07:30\"}"}}
            ]},"index":0}]}
        """.trimIndent()
        val parsed = SseParser.parseChunk(json, payload)!!
        val d = parsed.toolDeltas.single()
        assertEquals(0, d.index)
        assertNull(d.id) // continuation chunks carry no id
        assertNull(d.name)
        assertEquals("e\":\"07:30\"}", d.argsDelta)
    }

    @Test
    fun `finish reason is captured`() {
        val payload = """{"choices":[{"delta":{},"index":0,"finish_reason":"tool_calls"}]}"""
        val parsed = SseParser.parseChunk(json, payload)!!
        assertEquals("tool_calls", parsed.finishReason)
    }

    @Test
    fun `malformed json chunk is skipped`() {
        assertNull(SseParser.parseChunk(json, "not json at all"))
    }

    @Test
    fun `missing choices array is skipped`() {
        assertNull(SseParser.parseChunk(json, """{"object":"chat.completion.chunk"}"""))
    }

    // ------------------------------------------------------------------
    // Event assembly (audit #13): spec-compliant multi-line data payloads
    // ------------------------------------------------------------------

    @Test
    fun `single data line dispatches on the terminating blank line`() {
        val a = SseParser.EventAssembler()
        assertNull(a.offer("data: {\"a\":1}"))
        assertEquals("""{"a":1}""", a.offer(""))
        // Assembler is reset after dispatch.
        assertNull(a.offer(""))
    }

    @Test
    fun `multi-line data payloads are joined with newline per the sse spec`() {
        val a = SseParser.EventAssembler()
        assertNull(a.offer("data: first"))
        assertNull(a.offer("data: second"))
        assertNull(a.offer("data: third"))
        assertEquals("first\nsecond\nthird", a.offer(""))
    }

    @Test
    fun `comments and other field lines do not terminate or join the event`() {
        val a = SseParser.EventAssembler()
        assertNull(a.offer("data: payload"))
        assertNull(a.offer(": keep-alive"))
        assertNull(a.offer("event: message"))
        assertNull(a.offer("id: 42"))
        assertNull(a.offer("data: continued"))
        assertEquals("payload\ncontinued", a.offer(""))
    }

    @Test
    fun `flush emits a pending unterminated event and empties the assembler`() {
        val a = SseParser.EventAssembler()
        assertNull(a.offer("data: tail"))
        assertEquals("tail", a.flush())
        assertNull(a.flush())
    }

    @Test
    fun `multi-line assembled payload parses as one chunk`() {
        // A JSON payload split across two data: lines by a quirky proxy.
        val a = SseParser.EventAssembler()
        assertNull(a.offer("""data: {"choices":[{"delta":{"content":"При"}"""))
        assertNull(a.offer("""data: ,"index":0}]}"""))
        val payload = a.offer("")!!
        val parsed = SseParser.parseChunk(json, payload)!!
        assertEquals("При", parsed.text)
    }
}
