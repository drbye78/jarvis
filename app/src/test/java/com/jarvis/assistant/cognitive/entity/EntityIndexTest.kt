package com.jarvis.assistant.cognitive.entity

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityIndexTest {

    private fun fact(
        id: String,
        category: FactCategory,
        predicate: String,
        value: String,
        status: FactStatus = FactStatus.ACTIVE,
    ) = FactSnapshot(
        factId = id,
        category = category,
        subject = "user",
        predicate = predicate,
        value = value,
        valueNormalized = value,
        confidence = 0.9f,
        origin = FactOrigin.EXPLICIT,
        status = status,
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        createdAt = 1L,
        updatedAt = 1L,
        lastConfirmedAt = 1L,
        lastRecalledAt = null,
        recallCount = 0,
    )

    // ---- question → predicate mapping ----------------------------------

    @Test
    fun `who-questions map to relation predicates`() {
        assertEquals(setOf("boss"), EntityIndex.questionPredicates("кто мой начальник?"))
        assertEquals(setOf("boss"), EntityIndex.questionPredicates("Кто мой руководитель?"))
        assertEquals(setOf("spouse"), EntityIndex.questionPredicates("кто моя жена?"))
        assertEquals(setOf("spouse"), EntityIndex.questionPredicates("кто мой муж?"))
        assertEquals(setOf("child"), EntityIndex.questionPredicates("кто мой сын?"))
        assertEquals(setOf("parent"), EntityIndex.questionPredicates("а кто моя мама?"))
        assertEquals(setOf("friend"), EntityIndex.questionPredicates("кто мой друг?"))
        assertEquals(setOf("colleague"), EntityIndex.questionPredicates("кто мой коллега?"))
        assertEquals(setOf("pet"), EntityIndex.questionPredicates("кто мой кот?"))
    }

    @Test
    fun `self work and study questions map`() {
        assertEquals(
            setOf("works_at", "works_as"),
            EntityIndex.questionPredicates("где я работаю?"),
        )
        assertEquals(setOf("studies_at"), EntityIndex.questionPredicates("где я учусь?"))
    }

    @Test
    fun `declarative and unrelated utterances never match`() {
        assertTrue(EntityIndex.questionPredicates("мой начальник Иванов").isEmpty())
        assertTrue(EntityIndex.questionPredicates("какая сегодня погода?").isEmpty())
        assertTrue(EntityIndex.questionPredicates("включи джаз").isEmpty())
        assertTrue(EntityIndex.questionPredicates(null).isEmpty())
        assertTrue(EntityIndex.questionPredicates("").isEmpty())
    }

    // ---- recall boost ----------------------------------------------------

    @Test
    fun `boost lands only on the matching ACTIVE RELATION facts`() {
        val facts = listOf(
            fact("boss", FactCategory.RELATION, "boss", "Иванов"),
            fact("pref", FactCategory.PREFERENCE, "likes", "джаз"),
            fact("col", FactCategory.RELATION, "colleague", "Оля"),
            fact("gone", FactCategory.RELATION, "boss", "Петров", status = FactStatus.FORGOTTEN),
        )
        val boosted = EntityIndex.relationBoostFactIds(facts, "кто мой начальник?")
        assertEquals(setOf("boss"), boosted)
    }

    @Test
    fun `boost is empty without a question pattern`() {
        val facts = listOf(fact("boss", FactCategory.RELATION, "boss", "Иванов"))
        assertTrue(EntityIndex.relationBoostFactIds(facts, "скажи про Иванова").isEmpty())
    }

    // ---- derivation --------------------------------------------------------

    @Test
    fun `derivation merges entities on the normalized name`() {
        val derived = EntityIndex.deriveEntities(
            listOf(
                fact("f1", FactCategory.RELATION, "boss", "Иванов"),
                fact("f2", FactCategory.RELATION, "colleague", "иванов"), // same normalized name
                fact("f3", FactCategory.RELATION, "works_at", "Яндекс"),
                fact("f4", FactCategory.PREFERENCE, "likes", "джаз"), // not RELATION
                fact("f5", FactCategory.RELATION, "spouse", "Маша", status = FactStatus.SUPERSEDED),
            ),
        )
        val byName = derived.associateBy { it.nameNormalized }
        assertEquals(2, derived.size)
        assertEquals(setOf("f1", "f2"), byName.getValue("иванов").factIds.toSet())
        assertEquals(EntityIndex.EntityKind.ORG, byName.getValue("яндекс").kind)
        assertTrue(derived.none { it.nameNormalized == "маша" })
    }
}
