package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic caps: exact boundary behaviour for each budget, stable
 * truncation order and explicit truncation markers.
 */
class McpBudgetTest {

    @Test
    fun `cap tools keeps everything at or below the cap`() {
        val atCap = (0 until McpBudget.MAX_TOOLS_PER_SERVER).toList()
        assertEquals(atCap to 0, McpBudget.capTools(atCap))
        assertEquals(emptyList<Int>() to 0, McpBudget.capTools(emptyList<Int>()))
        assertEquals(listOf(1) to 0, McpBudget.capTools(listOf(1)))
    }

    @Test
    fun `cap tools truncates deterministically preserving encounter order`() {
        val input = (0 until McpBudget.MAX_TOOLS_PER_SERVER + 6).toList()
        val expectedKept = (0 until McpBudget.MAX_TOOLS_PER_SERVER).toList()
        val first = McpBudget.capTools(input)
        val second = McpBudget.capTools(input)
        assertEquals(expectedKept to 6, first)
        assertEquals(first, second)
    }

    @Test
    fun `cap tools boundary exactly over by one`() {
        val justOver = (0..McpBudget.MAX_TOOLS_PER_SERVER).toList()
        val (kept, truncated) = McpBudget.capTools(justOver)
        assertEquals(McpBudget.MAX_TOOLS_PER_SERVER, kept.size)
        assertEquals(1, truncated)
    }

    @Test
    fun `cap description leaves short text untouched`() {
        assertEquals("short", McpBudget.capDescription("short"))
        val atCap = "a".repeat(McpBudget.MAX_DESCRIPTION_CHARS)
        assertEquals(atCap, McpBudget.capDescription(atCap))
    }

    @Test
    fun `cap description truncates and marks over cap`() {
        val over = "a".repeat(McpBudget.MAX_DESCRIPTION_CHARS + 1)
        val capped = McpBudget.capDescription(over)
        assertNotEquals(over, capped)
        assertEquals(McpBudget.MAX_DESCRIPTION_CHARS, capped.length)
        assertTrue(capped.endsWith(McpBudget.DESCRIPTION_TRUNCATION_MARKER))
    }

    @Test
    fun `schema acceptability is byte based`() {
        assertEquals(McpBudget.MAX_SCHEMA_BYTES, "a".repeat(McpBudget.MAX_SCHEMA_BYTES).length)
        assertTrue(McpBudget.isSchemaAcceptable("a".repeat(McpBudget.MAX_SCHEMA_BYTES)))
        assertTrue(McpBudget.isSchemaAcceptable("a".repeat(McpBudget.MAX_SCHEMA_BYTES - 1)))
        assertFalse(McpBudget.isSchemaAcceptable("a".repeat(McpBudget.MAX_SCHEMA_BYTES + 1)))
    }

    @Test
    fun `schema acceptability counts utf8 bytes not chars`() {
        // 'я' is two UTF-8 bytes.
        assertTrue(McpBudget.isSchemaAcceptable("я".repeat(McpBudget.MAX_SCHEMA_BYTES / 2)))
        assertFalse(McpBudget.isSchemaAcceptable("я".repeat(McpBudget.MAX_SCHEMA_BYTES / 2 + 1)))
    }

    @Test
    fun `cap result leaves small output untouched`() {
        assertEquals("hello", McpBudget.capResult("hello"))
        val atCap = "a".repeat(McpBudget.MAX_RESULT_BYTES)
        assertEquals(atCap, McpBudget.capResult(atCap))
    }

    @Test
    fun `cap result truncates to the byte cap and marks it`() {
        val capped = McpBudget.capResult("a".repeat(McpBudget.MAX_RESULT_BYTES + 1))
        assertTrue(capped.toByteArray(Charsets.UTF_8).size <= McpBudget.MAX_RESULT_BYTES)
        assertTrue(capped.endsWith(McpBudget.RESULT_TRUNCATION_MARKER))
        assertTrue(capped.contains("truncated"))
        assertEquals(McpBudget.MAX_RESULT_BYTES, capped.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun `cap result handles multibyte code points without splitting them`() {
        val emoji = "\uD83D\uDE00" // U+1F600, four UTF-8 bytes
        val capped = McpBudget.capResult(emoji.repeat(McpBudget.MAX_RESULT_BYTES / 4 + 100))
        assertTrue(capped.toByteArray(Charsets.UTF_8).size <= McpBudget.MAX_RESULT_BYTES)
        assertTrue(capped.endsWith(McpBudget.RESULT_TRUNCATION_MARKER))
        // The retained prefix must be well-formed: no lone surrogate.
        val prefix = capped.removeSuffix(McpBudget.RESULT_TRUNCATION_MARKER)
        var highSurrogates = 0
        for (ch in prefix) {
            if (ch.isHighSurrogate()) highSurrogates++
            if (ch.isLowSurrogate()) highSurrogates--
        }
        assertEquals(0, highSurrogates)
    }
}
