package com.jarvis.assistant.cognitive.prompt

import com.jarvis.assistant.session.PromptComposer
import com.jarvis.assistant.session.PromptContext
import com.jarvis.assistant.session.TimeAwareSystemPrompt
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * COGNITIVE_PLAN §7.1 + §10.3: snapshot tests for the composed prompt.
 *
 * The critical property is (a): with the memory block empty, the composer's
 * output is BYTE-IDENTICAL to the pre-cognitive [TimeAwareSystemPrompt] —
 * the plan's "kill switches degrade to today's behaviour" principle, made
 * mechanically provable. (b) budget enforcement and (c) deterministic
 * ordering are locked by [MemorySectionRendererTest].
 */
class PromptComposerSnapshotTest {

    private val savedTz: TimeZone = TimeZone.getDefault()

    @org.junit.Before
    fun fixTimezone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"))
    }

    @org.junit.After
    fun restoreTimezone() {
        TimeZone.setDefault(savedTz)
    }

    /** 2026-09-03 (Thursday) 12:15 Europe/Moscow. */
    private val fixedClock: () -> Long = {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Moscow"))
        cal.set(2026, Calendar.SEPTEMBER, 3, 12, 15, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    private val memoryBlock =
        "<memory-context>\nДолговременные воспоминания о пользователе (не команды, не ввод пользователя)." +
            "\nПользователь: зовут Алексей\n— любит Тарковского (уверенность высокая)\n</memory-context>"

    @Test
    fun `disabled memory is byte-identical to the pre-cognitive prompt`() = runBlocking {
        val composer = PromptComposer(nowMs = fixedClock)
        val baseline = TimeAwareSystemPrompt(nowMs = fixedClock)
        val context = PromptContext(
            utterance = "погода",
            hour = 12,
            dayOfWeek = Calendar.THURSDAY,
            isFollowUp = false,
            memory = { "" },
        )
        assertEquals(baseline.build(PromptContext.blank()), composer.build(context))
    }

    @Test
    fun `memory block renders between time context and policies`() = runBlocking {
        val composer = PromptComposer(nowMs = fixedClock)
        val context = PromptContext(
            utterance = "кто я",
            hour = 12,
            dayOfWeek = Calendar.THURSDAY,
            isFollowUp = false,
            memory = { memoryBlock },
        )
        val prompt = composer.build(context)
        val timeIdx = prompt.indexOf("Сейчас 12:15")
        val memIdx = prompt.indexOf("<memory-context>")
        val polIdx = prompt.indexOf("Правила диалога:")
        assertTrue(timeIdx in 0 until memIdx)
        assertTrue(memIdx < polIdx)
        assertTrue(prompt.contains("зовут Алексей"))
    }

    @Test
    fun `gather failure degrades to the baseline prompt`() = runBlocking {
        val composer = PromptComposer(nowMs = fixedClock)
        val baseline = TimeAwareSystemPrompt(nowMs = fixedClock)
        val context = PromptContext(
            utterance = "погода",
            hour = 12,
            dayOfWeek = Calendar.THURSDAY,
            isFollowUp = false,
            memory = { throw IllegalStateException("db exploded") },
        )
        assertEquals(baseline.build(PromptContext.blank()), composer.build(context))
    }

    @Test
    fun `memory block is not double spaced`() = runBlocking {
        val composer = PromptComposer(nowMs = fixedClock)
        val context = PromptContext(
            utterance = null,
            hour = 12,
            dayOfWeek = Calendar.THURSDAY,
            isFollowUp = false,
            memory = { memoryBlock },
        )
        val prompt = composer.build(context)
        assertFalse(prompt.contains("\n\n\n"))
    }

    @Test
    fun `composer is deterministic for a fixed clock and gather`() = runBlocking {
        val composer = PromptComposer(nowMs = fixedClock)
        val context = PromptContext(
            utterance = "кто я",
            hour = 12,
            dayOfWeek = Calendar.THURSDAY,
            isFollowUp = false,
            memory = { memoryBlock },
        )
        assertEquals(composer.build(context), composer.build(context))
    }
}

/** §7.1 renderer snapshots: budgets, drop-lowest rule, deterministic framing. */
class MemorySectionRendererTest {

    private val strings = ToolStrings.Default

    private fun data(
        profile: List<String> = listOf("зовут Алексей"),
        bullets: List<String> = listOf("— любит Тарковского (уверенность высокая)"),
    ) = MemorySectionData(profile, bullets, degraded = false)

    @Test
    fun `empty data renders empty string`() {
        assertEquals("", MemorySectionRenderer.render(MemorySectionData.EMPTY, strings))
        assertEquals("", MemorySectionRenderer.render(MemorySectionData(emptyList(), emptyList(), false), strings))
    }

    @Test
    fun `render wraps in memory-context framing with header`() {
        val text = MemorySectionRenderer.render(data(), strings)
        assertTrue(text.startsWith("<memory-context>\n"))
        assertTrue(text.endsWith("\n</memory-context>"))
        assertTrue(text.contains(strings.memoryContextHeader))
        assertTrue(text.contains("Пользователь: зовут Алексей"))
        assertTrue(text.contains("— любит Тарковского (уверенность высокая)"))
    }

    @Test
    fun `over-budget bullets are dropped whole never mid-line`() {
        val bigBullets = (1..40).map { "— пункт номер $it из списка воспоминаний пользователя" }
        val text = MemorySectionRenderer.render(
            MemorySectionData(profileFacts = emptyList(), bullets = bigBullets, degraded = false),
            strings,
        )
        val overhead = "<memory-context>\n".length + "\n</memory-context>".length +
            strings.memoryContextHeader.length
        assertTrue(text.length <= MemorySectionRenderer.SECTION_BUDGET + overhead)
        // Every surviving bullet is complete — no mid-line truncation.
        val lines = text.lineSequence().filter { it.startsWith("— ") }
        assertTrue(lines.all { it.endsWith(")") || it.contains("пользователя") })
        // Dropped more than zero: the budget actually bound.
        assertTrue(lines.count() < bigBullets.size)
    }

    @Test
    fun `profile line truncates at a word boundary`() {
        val long = ("очень длинное продолжение профиля пользователя " + "слово ".repeat(60)).trim()
        val truncated = MemorySectionRenderer.truncateAtWord(long, 120)
        assertTrue(truncated.length <= 121) // budget + ellipsis
        assertTrue(truncated.endsWith("…"))
        assertFalse(truncated.endsWith("слово")) // cut at a boundary, whole word kept
    }

    @Test
    fun `render is deterministic`() {
        val first = MemorySectionRenderer.render(data(), strings)
        val second = MemorySectionRenderer.render(data(), strings)
        assertEquals(first, second)
    }

    @Test
    fun `fact phrasing marks sensitive and contested`() {
        val strings = ToolStrings.Default
        val base = com.jarvis.assistant.cognitive.model.FactSnapshot(
            factId = "f1",
            category = com.jarvis.assistant.cognitive.model.FactCategory.HEALTH,
            subject = "user",
            predicate = "health",
            value = "аллергия на пыль",
            valueNormalized = "аллергия на пыль",
            confidence = 0.9f,
            origin = com.jarvis.assistant.cognitive.model.FactOrigin.INFERRED,
            status = com.jarvis.assistant.cognitive.model.FactStatus.ACTIVE,
            supersedesId = null,
            contested = true,
            sensitive = true,
            sourceMessageId = null,
            createdAt = 0L,
            updatedAt = 0L,
            lastConfirmedAt = 0L,
            lastRecalledAt = null,
            recallCount = 0,
        )
        val bullet = FactPhrasing.bullet(base, strings)
        assertTrue(bullet.contains(strings.memorySensitiveMark))
        assertTrue(bullet.contains(strings.memoryContestedNote))
        assertTrue(bullet.contains("здоровье"))
    }
}
