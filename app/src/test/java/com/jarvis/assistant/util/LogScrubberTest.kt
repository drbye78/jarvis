package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.4 — decision matrix for [LogScrubber], the pattern-based redaction
 * [FileLoggingTree] applies to every persisted INFO+ line (defense-in-depth
 * behind the DEBUG-only convention; [SpeechContentLoggingTest] remains the
 * primary guard). Pure JVM — no Android Context.
 */
class LogScrubberTest {

    // ------------------------------------------------------------------
    // Rule 1: content keys get their value redacted
    // ------------------------------------------------------------------

    @Test
    fun `content keys - key assignment shapes are redacted`() {
        assertEquals("utterance=<redacted>", LogScrubber.scrub("utterance=на что-то длинное"))
        assertEquals("text=<redacted>", LogScrubber.scrub("text=привет"))
        assertEquals("sentence=<redacted>", LogScrubber.scrub("sentence: ой, всё"))
        assertEquals("query=<redacted>", LogScrubber.scrub("query = включи свет"))
        assertEquals("Prompt=<redacted>", LogScrubber.scrub("Prompt=system secret"))
        assertFalse(
            "content keys are case-insensitive",
            LogScrubber.scrub("reply: секретный текст").contains("секретный")
        )
    }

    @Test
    fun `content keys - redaction reaches end of line, key survives`() {
        assertEquals(
            "TTS sentence failed (len=12) text=<redacted>",
            LogScrubber.scrub("TTS sentence failed (len=12) text=вот секретная фраза"),
        )
    }

    @Test
    fun `content keys - non-content lines pass through untouched`() {
        assertEquals(
            "Watchdog: audio pipeline gave up — reviving capture (attempt 1/3 today)",
            LogScrubber.scrub("Watchdog: audio pipeline gave up — reviving capture (attempt 1/3 today)"),
        )
        // scope= / code= / len= are NOT content keys.
        assertEquals(
            "Token refresh failed: HTTP 401 for scope=GIGACHAT_API_PERS",
            LogScrubber.scrub("Token refresh failed: HTTP 401 for scope=GIGACHAT_API_PERS"),
        )
    }

    // ------------------------------------------------------------------
    // Rule 2: quoted spans of 6+ chars are redacted
    // ------------------------------------------------------------------

    @Test
    fun `quoted spans of six-plus chars are redacted in guillemets and double quotes`() {
        assertEquals(
            "onSearch unsupported or failed for «<redacted>»",
            LogScrubber.scrub("onSearch unsupported or failed for «включи музыку побыстрее»"),
        )
        assertEquals(
            "Alert scheduled: timer \"<redacted>\" at 42 (id=7)",
            LogScrubber.scrub("Alert scheduled: timer \"моё напоминание\" at 42 (id=7)"),
        )
    }

    @Test
    fun `short and single-quoted spans survive - redaction is not over-eager`() {
        assertEquals(
            "Voice stop ('стоп') in state=SPEAKING — cancelling the turn",
            LogScrubber.scrub("Voice stop ('стоп') in state=SPEAKING — cancelling the turn"),
        )
        assertEquals("status=\"IDLE\" ok", LogScrubber.scrub("status=\"IDLE\" ok"))
    }

    // ------------------------------------------------------------------
    // Stack-trace / whole-line path
    // ------------------------------------------------------------------

    @Test
    fun `scrub keeps stack frames while redacting matched content in them`() {
        val stackTraceLike = """
            java.lang.RuntimeException: utterance: СЕКРЕТНАЯ ФРАЗА
              at com.jarvis.assistant.SessionManager.turn(SessionManager.kt:101)
              at java.lang.Thread.run(Thread.java:923)
        """.trimIndent()
        val scrubbed = LogScrubber.scrub(stackTraceLike)
        assertFalse(scrubbed.contains("СЕКРЕТНАЯ"))
        assertTrue("frames survive", scrubbed.contains("SessionManager.kt:101"))
        assertTrue(scrubbed.contains("utterance=<redacted>"))
    }

    @Test
    fun `REDACTED token is used consistently`() {
        assertTrue(LogScrubber.scrub("label: секрет").endsWith(LogScrubber.REDACTED))
    }
}
