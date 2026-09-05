package com.jarvis.assistant.cognitive.recall

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the plan §7.2 ranking function: weights, recency
 * half-life, per-category spread, deterministic ordering.
 */
class FactRankerTest {

    private val now = 1_700_000_000_000L
    private val ranker = FactRanker(nowMs = { now })

    private fun fact(
        factId: String,
        category: FactCategory,
        value: String,
        confidence: Float = 0.9f,
        ageDays: Long = 0,
        recallCount: Int = 0,
        status: FactStatus = FactStatus.ACTIVE,
    ) = FactSnapshot(
        factId = factId,
        category = category,
        subject = "user",
        predicate = "p_$factId",
        value = value,
        valueNormalized = SearchTokenizer.normalize(value),
        confidence = confidence,
        origin = FactOrigin.INFERRED,
        status = status,
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        createdAt = now - (ageDays + 1) * 86_400_000L,
        updatedAt = now - ageDays * 86_400_000L,
        lastConfirmedAt = now,
        lastRecalledAt = null,
        recallCount = recallCount,
    )

    @Test
    fun `score weights follow the plan formula`() {
        val f = fact("f1", FactCategory.IDENTITY, "зовут Алексей", confidence = 0.8f)
        val expected = 0.35f * 0.8f + 0.25f * 1f + 0.15f * 0f + 0.15f * 1f + 0.10f * 0f
        assertEquals(expected, ranker.score(f, emptySet()), 1e-5f)
    }

    @Test
    fun `recency decay halves every sixty days`() {
        val fresh = FactRanker.recencyDecay(now, now)
        val sixtyDays = FactRanker.recencyDecay(now - 60L * 86_400_000L, now)
        assertEquals(1f, fresh, 1e-6f)
        assertEquals(0.5f, sixtyDays, 0.01f)
    }

    @Test
    fun `usage term saturates at ten recalls`() {
        assertTrue(FactRanker.usageTerm(0) == 0f)
        assertTrue(FactRanker.usageTerm(10) in 0.99f..1f)
        assertTrue(FactRanker.usageTerm(1000) <= 1f)
    }

    @Test
    fun `identity outranks old weak preferences`() {
        val name = fact("name", FactCategory.IDENTITY, "зовут Алексей", confidence = 0.95f, ageDays = 90)
        val music = fact("music", FactCategory.PREFERENCE, "люблю джаз", confidence = 0.5f, ageDays = 120)
        val ranked = ranker.topFacts(listOf(music, name), utterance = null)
        assertEquals("name", ranked.first().fact.factId)
    }

    @Test
    fun `per category spread caps one category at two facts`() {
        val prefs = (1..5).map {
            fact("pref$it", FactCategory.PREFERENCE, "любит жанр номер $it", confidence = 0.9f - it * 0.01f)
        }
        val identity = fact("id1", FactCategory.IDENTITY, "зовут Алексей")
        val ranked = ranker.topFacts(prefs + identity, utterance = null, limit = 5)
        // 5 prefs → only the top 2 pass the spread cap; the identity fact
        // fills a third slot; nothing else remains → 3 facts total.
        assertEquals(3, ranked.size)
        assertTrue(ranked.count { it.fact.category == FactCategory.PREFERENCE } <= 2)
        assertEquals("id1", ranked.first().fact.factId)
    }

    @Test
    fun `ordering is deterministic on score ties`() {
        val a = fact("aaa", FactCategory.OTHER, "факт один")
        val b = fact("bbb", FactCategory.OTHER, "факт два")
        val ranked = ranker.topFacts(listOf(b, a), utterance = null)
        assertEquals(listOf("aaa", "bbb"), ranked.map { it.fact.factId })
    }

    @Test
    fun `non active facts are never ranked`() {
        val archived = fact("arc", FactCategory.IDENTITY, "зовут Алексей", status = FactStatus.ARCHIVED)
        val forgotten = fact("forg", FactCategory.OTHER, "пин код", status = FactStatus.FORGOTTEN)
        assertTrue(ranker.topFacts(listOf(archived, forgotten), null).isEmpty())
    }

    @Test
    fun `utterance tokens contribute overlap term`() {
        val tarkovsky = fact("tark", FactCategory.PREFERENCE, "фильмы Тарковского")
        val weather = fact("weat", FactCategory.OTHER, "любит узнаавать погоду")
        val utterance = "поставь фильмы Тарковского"
        val ranked = ranker.topFacts(listOf(weather, tarkovsky), utterance)
        assertEquals("tark", ranked.first().fact.factId)
        assertFalse(ranked.first().lexicalHit) // FTS boost is the caller's job
    }
}
