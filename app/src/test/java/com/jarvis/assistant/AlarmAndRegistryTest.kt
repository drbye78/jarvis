package com.jarvis.assistant

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.AlarmTimes
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AlarmTimesTest {

    @Test
    fun `parse valid times`() {
        assertEquals(7 to 30, AlarmTimes.parseTime("07:30"))
        assertEquals(0 to 0, AlarmTimes.parseTime("00:00"))
        assertEquals(23 to 59, AlarmTimes.parseTime("23:59"))
        assertEquals(9 to 5, AlarmTimes.parseTime("9:5"))
    }

    @Test
    fun `parse invalid times`() {
        assertEquals(null, AlarmTimes.parseTime("24:00"))
        assertEquals(null, AlarmTimes.parseTime("12:60"))
        assertEquals(null, AlarmTimes.parseTime("12-30"))
        assertEquals(null, AlarmTimes.parseTime("abc"))
        assertEquals(null, AlarmTimes.parseTime(""))
    }

    @Test
    fun `next occurrence rolls to tomorrow when time passed`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 24, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis

        val later = AlarmTimes.nextOccurrence(13, 0, now)
        assertEquals(now + 60 * 60 * 1000L, later)

        val tomorrow = AlarmTimes.nextOccurrence(11, 0, now)
        assertEquals(now + 23 * 60 * 60 * 1000L, tomorrow)
    }

    @Test
    fun `next occurrence at exact current time rolls forward`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 24, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        // at-or-after semantics: 12:00 requested at 12:00.000 -> tomorrow
        val next = AlarmTimes.nextOccurrence(12, 0, now)
        assertEquals(now + 24 * 60 * 60 * 1000L, next)
    }
}

class ToolRegistryTest {

    private class OkTool : ToolContract {
        override val name = "ok"
        override val description = "ok tool"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String) = """{"status":"ok"}"""
    }

    private class ThrowingTool : ToolContract {
        override val name = "boom"
        override val description = "throws"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String = throw IllegalStateException("kaput")
    }

    private class HangingTool : ToolContract {
        override val name = "hang"
        override val description = "hangs"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String {
            delay(60_000)
            return "{}"
        }
    }

    @Test
    fun `unknown tool produces error result`() = runBlocking {
        val registry = ToolRegistry(listOf(OkTool()))
        val result = registry.execute(FunctionCall("nope", "{}"))
        assertTrue(result.isError)
        assertTrue(result.result.contains("Unknown function"))
    }

    @Test
    fun `tool exception is converted to error result`() = runBlocking {
        val registry = ToolRegistry(listOf(ThrowingTool()))
        val result = registry.execute(FunctionCall("boom", "{}"))
        assertTrue(result.isError)
        assertTrue(result.result.contains("kaput"))
    }

    @Test
    fun `hanging tool times out`() = runBlocking {
        val registry = ToolRegistry(listOf(HangingTool()), perToolTimeoutMs = 100)
        val result = registry.execute(FunctionCall("hang", "{}"))
        assertTrue(result.isError)
        assertTrue(result.result.contains("timed out"))
    }

    @Test
    fun `tool definitions parse parameters into objects`() = runBlocking {
        val registry = ToolRegistry(listOf(OkTool()))
        val defs = registry.getToolDefinitions()
        assertEquals(1, defs.size)
        assertEquals("ok", defs[0].name)
        assertEquals(
            "object",
            (defs[0].parameters["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }

    /** Success payload that legitimately CONTAINS the literal `"error` text —
     *  the old substring sniffing misclassified this as an error (m1). */
    private class ErrorWordTool : ToolContract {
        override val name = "errword"
        override val description = "payload mentions error"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String) =
            """{"error_history":["old"],"status":"ok"}"""
    }

    @Test
    fun `success payload mentioning error key is not flagged as error`() = runBlocking {
        val registry = ToolRegistry(listOf(ErrorWordTool()))
        val structured = registry.executeResult(FunctionCall("errword", "{}"))
        assertFalse(structured.isError)
        // Legacy facade agrees with the structured classification.
        assertFalse(registry.execute(FunctionCall("errword", "{}")).isError)
    }

    @Test
    fun `executeResult classifies outcomes via the isError flag`() = runBlocking {
        val registry =
            ToolRegistry(listOf(OkTool(), ThrowingTool(), HangingTool()), perToolTimeoutMs = 100)

        assertFalse(registry.executeResult(FunctionCall("ok", "{}")).isError)
        assertTrue(registry.executeResult(FunctionCall("boom", "{}")).isError)
        assertTrue(registry.executeResult(FunctionCall("hang", "{}")).isError)
        assertTrue(registry.executeResult(FunctionCall("nope", "{}")).isError)
    }

    /** MUSIC lane: a tool may override the registry-wide timeout
     *  (playMusic legitimately needs ~30 s for the cold-start cascade). */
    private class SlowButOverrideTool : ToolContract {
        override val name = "slowOverride"
        override val description = "slow but allowed"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override val timeoutMs: Long = 60_000
        override suspend fun execute(arguments: String): String {
            delay(1_000)
            return """{"status":"late"}"""
        }
    }

    @Test
    fun `per-tool timeout override beats the registry default`() = runBlocking {
        // Registry default 100 ms would kill a 1 s tool — the override saves it.
        val registry = ToolRegistry(listOf(SlowButOverrideTool()), perToolTimeoutMs = 100)
        val result = registry.execute(FunctionCall("slowOverride", "{}"))
        assertFalse(result.isError)
        assertTrue(result.result.contains("late"))

        // Without an override the same duration still times out.
        val registry2 = ToolRegistry(listOf(HangingTool()), perToolTimeoutMs = 100)
        assertTrue(registry2.execute(FunctionCall("hang", "{}")).isError)
    }
}
