package com.jarvis.assistant

import com.jarvis.assistant.model.ToolCallSlip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-only pins for [ToolCallSlip], the shape-based leaked-tool-call scrub.
 *
 * The primary positive cases use the OBSERVED device shape, where the tool name
 * and the JSON payload sit on SEPARATE lines — a regression shipped when the
 * tests encoded the one-line assumption instead. The decisive negative is the
 * legitimate multi-line code block, which must come back byte-identical.
 */
class ToolCallSlipTest {

    private val names = setOf("getWeather", "setAlarm", "playMusic")

    @Test
    fun `fenced block with the name on its own line is recognized and strips to blank`() {
        // Exactly what the device recorded (row 76).
        val block = "```\ngetWeather\n{\"days\":1,\"location\":\"\"}\n```"
        assertTrue(ToolCallSlip.hasSlip(block, names))
        assertTrue(ToolCallSlip.strip(block, names).isBlank())
    }

    @Test
    fun `fenced block with the name on its own line and surrounding prose is scrubbed`() {
        val text = "Сейчас проверю.\n```\ngetWeather\n{\"location\":\"\"}\n```\nГотово."
        val out = ToolCallSlip.strip(text, names)
        assertFalse(out.contains("getWeather"))
        assertFalse(out.contains("location"))
        assertTrue(out.contains("Сейчас проверю."))
        assertTrue(out.contains("Готово."))
    }

    @Test
    fun `single-line payload inside the fence is still recognized`() {
        val block = "```\ngetWeather {\"location\":\"\"}\n```"
        assertTrue(ToolCallSlip.hasSlip(block, names))
        assertTrue(ToolCallSlip.strip(block, names).isBlank())
    }

    @Test
    fun `inline leaked call is recognized and strips to blank`() {
        val inline = "```getWeather {\"location\":\"\"}```"
        assertTrue(ToolCallSlip.hasSlip(inline, names))
        assertTrue(ToolCallSlip.strip(inline, names).isBlank())
    }

    @Test
    fun `opening info string is accepted`() {
        val info = "```json\ngetWeather\n{\"location\":\"\"}\n```"
        assertTrue(ToolCallSlip.hasSlip(info, names))
        assertTrue(ToolCallSlip.strip(info, names).isBlank())
    }

    @Test
    fun `multiple blocks are all removed and surrounding prose preserved`() {
        val text = listOf(
            "Before",
            "```",
            "getWeather",
            "{\"location\":\"\"}",
            "```",
            "Middle",
            "```json",
            "setAlarm",
            "{\"time\":\"07:30\"}",
            "```",
            "After",
        ).joinToString("\n")
        val out = ToolCallSlip.strip(text, names)
        assertFalse(out.contains("getWeather"))
        assertFalse(out.contains("setAlarm"))
        assertTrue(out.contains("Before"))
        assertTrue(out.contains("Middle"))
        assertTrue(out.contains("After"))
    }

    // ----------------------------------------------------------------
    // Falsification: the guard that must NOT fire
    // ----------------------------------------------------------------

    @Test
    fun `legitimate multi-line fenced code block is untouched byte-identically`() {
        val legit = "Here is code:\n```kotlin\nval x = 1\nval y = 2\n```\nDone"
        assertFalse(ToolCallSlip.hasSlip(legit, names))
        assertEquals(legit, ToolCallSlip.strip(legit, names))
    }

    @Test
    fun `fenced payload without an identifier is not a slip`() {
        val noId = "```\n{\"x\":1}\n```"
        assertFalse(ToolCallSlip.hasSlip(noId, names))
        assertEquals(noId, ToolCallSlip.strip(noId, names))
    }

    @Test
    fun `unknown identifier is not a slip`() {
        val unknown = "```\nfoo\n{\"x\":1}\n```"
        assertFalse(ToolCallSlip.hasSlip(unknown, setOf("getWeather")))
        assertEquals(unknown, ToolCallSlip.strip(unknown, setOf("getWeather")))
    }

    @Test
    fun `unterminated fence is not a slip`() {
        val unterminated = "```\ngetWeather\n{\"location\":\"\"}"
        assertFalse(ToolCallSlip.hasSlip(unterminated, names))
        assertEquals(unterminated, ToolCallSlip.strip(unterminated, names))
    }

    // ----------------------------------------------------------------
    // Edges
    // ----------------------------------------------------------------

    @Test
    fun `malformed payload does not throw and the block is removed`() {
        val malformed = "```\ngetWeather\n{not json}\n```"
        val out = ToolCallSlip.strip(malformed, names)
        assertFalse(out.contains("getWeather"))
        assertFalse(out.contains("not json"))
        assertTrue(out.isBlank())
    }

    @Test
    fun `empty names disables the scrub entirely`() {
        val block = "```\ngetWeather\n{\"location\":\"\"}\n```"
        assertFalse(ToolCallSlip.hasSlip(block, emptySet()))
        assertEquals(block, ToolCallSlip.strip(block, emptySet()))
    }
}
