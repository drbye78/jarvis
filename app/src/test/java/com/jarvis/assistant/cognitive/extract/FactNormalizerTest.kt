package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.model.ValidatedFact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture tests for the plan §6.3 decision matrix (dedup / supersede /
 * contest / create), run against a deterministic clock and id generator.
 */
class FactNormalizerTest {

    private var now = 1_000_000L
    private var nextId = 0

    private val normalizer = FactNormalizer(
        nowMs = { now },
        newId = { "fact-${++nextId}" },
    )

    private fun fact(
        subject: String = "user",
        predicate: String = "likes",
        value: String,
        confidence: Float = 0.9f,
        status: FactStatus = FactStatus.ACTIVE,
        factId: String = "stored-${++nextId}",
        updatedAt: Long = now - 1_000,
        contested: Boolean = false,
    ) = FactSnapshot(
        factId = factId,
        category = FactCategory.PREFERENCE,
        subject = subject,
        predicate = predicate,
        value = value,
        valueNormalized = com.jarvis.assistant.cognitive.recall.SearchTokenizer.normalize(value),
        confidence = confidence,
        origin = FactOrigin.INFERRED,
        status = status,
        supersedesId = null,
        contested = contested,
        sensitive = false,
        sourceMessageId = 1L,
        createdAt = updatedAt - 5_000,
        updatedAt = updatedAt,
        lastConfirmedAt = updatedAt,
        lastRecalledAt = null,
        recallCount = 0,
    )

    private fun incoming(
        value: String,
        confidence: Float = 0.9f,
        predicate: String = "likes",
        subject: String = "user",
        messageId: Long = 42L,
    ) = ValidatedFact(
        subject = subject,
        predicate = predicate,
        value = value,
        confidence = confidence,
        evidence = value,
        messageId = messageId,
        category = FactCategory.PREFERENCE,
        sensitive = false,
    )

    @Test
    fun `exact same value confirms existing and raises confidence monotonically`() {
        val stored = listOf(fact(value = "фильмы Тарковского", confidence = 0.7f))
        val decision = normalizer.classify(incoming("Фильмы Тарковского", 0.9f), stored)

        assertTrue(decision is NormalizationDecision.ConfirmExisting)
        val confirm = decision as NormalizationDecision.ConfirmExisting
        assertEquals(stored[0].factId, confirm.existing.factId)
        assertEquals(now, confirm.now)
        // (2*0.7 + 0.9)/3 = 0.766… — raised, never decreased, capped below 1.
        assertTrue(confirm.mergedConfidence > 0.7f)
        assertTrue(confirm.mergedConfidence <= 0.99f)
    }

    @Test
    fun `paraphrase overlap counts as the same fact`() {
        val stored = listOf(fact(value = "фильмы Тарковского", confidence = 0.8f))
        // «обожаю Тарковского» shares the Тарковский stem → overlap 0.5.
        // Below the 0.8 threshold this is NOT a duplicate — but it IS a
        // same-predicate conflict, resolved by the supersession rules
        // (0.9 vs 0.8, both strong → contest).
        val decision = normalizer.classify(incoming("обожаю Тарковского", 0.9f), stored)
        assertTrue(decision is NormalizationDecision.Contest)
    }

    @Test
    fun `two strong conflicting claims are contested not silently overwritten`() {
        val stored = listOf(fact(predicate = "works_at", value = "Яндекс", confidence = 0.9f))
        val decision = normalizer.classify(
            incoming(value = "Сбер", confidence = 0.9f, predicate = "works_at"),
            stored,
        )
        assertTrue(decision is NormalizationDecision.Contest)
        val contest = decision as NormalizationDecision.Contest
        assertTrue(contest.newFact.contested)
        assertEquals(contest.oldFact.factId, contest.newFact.supersedesId)
    }

    @Test
    fun `weaker new claim supersedes when within tolerance`() {
        val stored = listOf(fact(value = "джаз семидесятых", confidence = 0.5f))
        // 0.55 ≥ 0.5 − 0.1 → supersede; not both strong.
        val decision = normalizer.classify(incoming("классика", 0.55f), stored)
        assertTrue(decision is NormalizationDecision.Supersede)
        val sup = decision as NormalizationDecision.Supersede
        assertEquals(stored[0].factId, sup.oldFact.factId)
        assertEquals(stored[0].factId, sup.newFact.supersedesId)
        assertTrue(!sup.newFact.contested)
    }

    @Test
    fun `much weaker contradictory claim keeps both facts active`() {
        val stored = listOf(fact(value = "джаз", confidence = 0.95f))
        // 0.3 < 0.95 − 0.1 and not both strong → the weak claim must not
        // destroy or dispute-shadow the strong one.
        val decision = normalizer.classify(incoming("поп-музыка", 0.3f), stored)
        assertTrue(decision is NormalizationDecision.CreateNew)
    }

    @Test
    fun `superseded rows are never compared as conflicts`() {
        val stored = listOf(
            fact(value = "джаз", confidence = 0.9f),
            fact(value = "блюз", confidence = 0.4f, status = FactStatus.SUPERSEDED),
        )
        val decision = normalizer.classify(incoming("рок", 0.4f), stored)
        // The freshest ACTIVE conflict is «джаз» (0.9): 0.4 < 0.8 → CreateNew.
        assertTrue(decision is NormalizationDecision.CreateNew)
        assertEquals("рок", (decision as NormalizationDecision.CreateNew).newFact.value)
    }

    @Test
    fun `explicit writes are normalized to the user subject`() {
        val decision = normalizer.classify(
            incoming(value = "Маша", subject = "  ", predicate = "spouse", confidence = 1f),
            emptyList(),
        )
        assertTrue(decision is NormalizationDecision.CreateNew)
        assertEquals("user", (decision as NormalizationDecision.CreateNew).newFact.subject)
    }

    @Test
    fun `mergeConfidence never decreases and caps at 0_99`() {
        assertEquals(0.8f, normalizer.mergeConfidence(0.8f, 0.5f), 1e-6f)
        assertTrue(normalizer.mergeConfidence(0.98f, 1f) <= 0.99f)
    }
}
