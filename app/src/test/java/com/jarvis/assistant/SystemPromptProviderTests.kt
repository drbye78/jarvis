package com.jarvis.assistant

import com.jarvis.assistant.session.TimeAwareSystemPrompt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * G1: the system prompt is now COMPOSED (identity + live time + policies +
 * tool routing) instead of one static literal. These tests pin every section
 * the dialogue audit asked for, with a fixed clock + timezone so the output
 * is byte-stable.
 */
class SystemPromptProviderTests {

    private val savedTz: TimeZone = TimeZone.getDefault()

    @Before
    fun fixTimezone() {
        // Deterministic weekday/date names regardless of the CI runner's zone.
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    @After
    fun restoreTimezone() {
        TimeZone.setDefault(savedTz)
    }

    /** 2026-09-03 (Thursday) 03:15 Europe/Moscow — epoch millis. */
    private fun at(h: Int, m: Int): () -> Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        cal.set(2026, Calendar.SEPTEMBER, 3, h, m, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return { cal.timeInMillis }
    }

    private fun promptAt(h: Int, m: Int = 15): String =
        TimeAwareSystemPrompt(nowMs = at(h, m)).build()

    @Test
    fun `time line carries clock time, weekday and date`() {
        val prompt = promptAt(3)
        // "Сейчас 03:15, четверг, 3 сентября. …"
        assertTrue("missing clock time: $prompt", Regex("Сейчас 03:15,").containsMatchIn(prompt))
        assertTrue("missing weekday: $prompt", prompt.contains("четверг"))
        assertTrue("missing date: $prompt", prompt.contains("3 сентября"))
    }

    @Test
    fun `deep night hint asks for shorter answers`() {
        val prompt = promptAt(3)
        assertTrue(prompt.contains("глубокая ночь"))
        assertTrue(prompt.contains("короче"))
    }

    @Test
    fun `time of day hints cover the day`() {
        assertTrue(promptAt(8).contains("Сейчас утро."))
        assertTrue(promptAt(13).contains("Сейчас день."))
        assertTrue(promptAt(19).contains("Сейчас вечер."))
        assertTrue(promptAt(23).contains("поздний вечер"))
    }

    @Test
    fun `identity and language policy preserved`() {
        val prompt = promptAt(12)
        assertTrue(prompt.contains("Ты — Джарвис, голосовой ассистент на планшете Android."))
        assertTrue(prompt.contains("ВСЕГДА на русском языке"))
        // Personality beyond "short and conversational" (audit gap).
        assertTrue(prompt.contains("Характер:"))
    }

    @Test
    fun `clarification policy present`() {
        assertTrue(promptAt(12).contains("уточняющий вопрос"))
    }

    @Test
    fun `confirmation policy for irreversible actions present`() {
        val prompt = promptAt(12)
        assertTrue(prompt.contains("Необратимое действие"))
        assertTrue(prompt.contains("подтверди"))
    }

    @Test
    fun `safety refusal policy present`() {
        val prompt = promptAt(12)
        assertTrue(prompt.contains("не помогай", ignoreCase = true) || prompt.contains("Не помогай"))
        assertTrue(prompt.contains("взлом"))
    }

    @Test
    fun `no tech details policy preserved`() {
        assertTrue(promptAt(12).contains("Не упоминай технические детали"))
    }

    @Test
    fun `music tool routing moved intact`() {
        val prompt = promptAt(12)
        assertTrue(prompt.contains("playMusic"))
        assertTrue(prompt.contains("controlPlayback"))
        assertTrue(prompt.contains("getNowPlaying"))
        assertTrue(prompt.contains("listPlaylists"))
        assertTrue(prompt.contains("searchLibrary"))
        assertTrue(prompt.contains("deltaMs"))
    }

    @Test
    fun `prompt stays a single compact page - no runaway growth`() {
        // ~2K chars ≈ 500-600 tokens: the prompt must not eat the context.
        val len = promptAt(12).length
        assertTrue("prompt too large: $len chars", len < 2500)
        assertTrue("prompt suspiciously small: $len chars", len > 700)
        // No double blank-line runs from section composition.
        assertFalse(promptAt(12).contains("\n\n\n"))
    }

    @Test
    fun `build is stable for a fixed clock`() {
        val p = TimeAwareSystemPrompt(nowMs = at(10, 30))
        assertEquals(p.build(), p.build())
    }
}
