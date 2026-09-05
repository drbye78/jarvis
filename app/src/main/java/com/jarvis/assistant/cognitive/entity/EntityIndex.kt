package com.jarvis.assistant.cognitive.entity

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * COGNITIVE_PLAN Phase 3 (§11: "entity/relation derivation from RELATION
 * facts + recall integration («кто мой начальник?»)"). Pure Kotlin.
 *
 * Two cooperating parts:
 *
 * 1. [deriveEntities] — the two-table entity model (plan §12.3: "two-table
 *    entity model suffices at this scale"): named things mentioned by
 *    ACTIVE RELATION facts (Иванов the boss, Яндекс the employer, Маша the
 *    spouse), deduplicated on the normalized name. Derived in nightly
 *    maintenance and after backfill; stored in `entities` +
 *    `fact_entities` (v6) for the inspector and future consumers.
 *
 * 2. [relationBoostFactIds] — the recall integration. A «кто мой X?» /
 *    «где я работаю» question maps X onto the extraction predicate
 *    vocabulary (ExtractionContract.PREDICATE_CATEGORIES) through a small,
 *    conservative RU/EN synonym table; matching ACTIVE RELATION facts get
 *    the same flat boost an FTS hit gets. This works DIRECTLY on the fact
 *    snapshots the gather already loaded — no entity-table read on the hot
 *    path, hence no way for derivation lag to corrupt recall.
 *
 * Honest scope: the synonym table is deliberately small and test-pinned —
 * it answers the canonical phrasings, everything else stays with the LLM
 * (the plan's "the LLM is the NLU" principle). Miss = no boost, never a
 * wrong boost.
 */
object EntityIndex {

    /** RELATION predicates per ExtractionContract.PREDICATE_CATEGORIES. */
    val RELATION_PREDICATES: Set<String> = setOf(
        "works_at", "works_as", "studies_at",
        "boss", "colleague", "spouse", "child", "parent", "friend", "pet",
    )

    /**
     * Question head → predicate. Keys are WORDS, stemmed through
     * [SearchTokenizer.stem] once at init so lookups match the stemmed
     * query tokens exactly («жена» → "жен") — deterministic, no substring
     * surprises.
     */
    private val HEAD_TO_PREDICATE: Map<String, Set<String>> = mapOf(
        // «кто мой начальник/руководитель/шеф/директор?»
        "начальник" to setOf("boss"),
        "начальница" to setOf("boss"),
        "руководитель" to setOf("boss"),
        "шеф" to setOf("boss"),
        "директор" to setOf("boss"),
        // «кто моя жена/супруга?», «кто мой муж/супруг?»
        "жена" to setOf("spouse"),
        "супруга" to setOf("spouse"),
        "супруг" to setOf("spouse"),
        "муж" to setOf("spouse"),
        // «кто мой ребёнок/сын/дочь?»
        "ребенок" to setOf("child"),
        "сын" to setOf("child"),
        "дочь" to setOf("child"),
        "дочка" to setOf("child"),
        // «кто моя мама/мать?», «кто мой папа/отец?»
        "мама" to setOf("parent"),
        "мать" to setOf("parent"),
        "папа" to setOf("parent"),
        "отец" to setOf("parent"),
        "родители" to setOf("parent"),
        // «кто мой друг/подруга/коллега?»
        "друг" to setOf("friend"),
        "подруга" to setOf("friend"),
        "коллега" to setOf("colleague"),
        "товарищ" to setOf("friend"),
        // «кто мой кот/пёс/питомец?»
        "кот" to setOf("pet"),
        "кошка" to setOf("pet"),
        "пес" to setOf("pet"),
        "собака" to setOf("pet"),
        "питомец" to setOf("pet"),
    ).mapKeys { (word, _) -> SearchTokenizer.stem(word) }

    /** «где я работаю» / «кем я работаю» / «где я учусь» self-questions. */
    private val SELF_QUESTION_PREDICATES: Map<String, Set<String>> = mapOf(
        "работаю" to setOf("works_at", "works_as"),
        "работу" to setOf("works_at", "works_as"),
        "учусь" to setOf("studies_at"),
        "учеба" to setOf("studies_at"),
    ).mapKeys { (word, _) -> SearchTokenizer.stem(word) }

    /** Question stems that license a «кто мой X» reading. */
    private val WHO_QUESTION_STEMS = setOf("кто", "who")

    private val POSSESSIVE_STEMS = setOf("мой", "мо", "моя", "мои", "мое", "my")

    /**
     * FactIds of ACTIVE RELATION facts whose predicate answers the
     * utterance's relation question. Empty unless a question pattern is
     * actually present — declarative utterances are never boosted.
     */
    fun relationBoostFactIds(
        facts: List<FactSnapshot>,
        utterance: String?,
    ): Set<String> {
        val predicates = questionPredicates(utterance)
        if (predicates.isEmpty()) return emptySet()
        return facts.asSequence()
            .filter { it.status == FactStatus.ACTIVE }
            .filter { it.category == FactCategory.RELATION }
            .filter { it.predicate in predicates }
            .map { it.factId }
            .toSet()
    }

    /**
     * The matched predicate keys for an utterance, or empty. Two licensed
     * shapes: «(кто) (мой) <head>» and «где/кем я работаю/учусь».
     */
    fun questionPredicates(utterance: String?): Set<String> {
        if (utterance.isNullOrBlank()) return emptySet()
        val stems = SearchTokenizer.tokens(utterance)
        if (stems.isEmpty()) return emptySet()

        val result = mutableSetOf<String>()

        // Shape 1: «кто мой <head>» — the question stem plus a possessive
        // within the same short utterance, head right after the possessive.
        if (stems.any { it in WHO_QUESTION_STEMS }) {
            stems.forEachIndexed { index, stem ->
                if (stem in POSSESSIVE_STEMS) {
                    val head = stems.getOrNull(index + 1) ?: return@forEachIndexed
                    HEAD_TO_PREDICATE[head]?.let { result.addAll(it) }
                }
            }
            // Head-first order («кто такой Иванов» must not match; but
            // «мой начальник кто?» survives via the scan above).
        }

        // Shape 2: explicit self-work/study questions.
        if (stems.firstOrNull() in SELF_QUESTION_PREDICATES.keys ||
            stems.contains("где") || stems.contains("кем")
        ) {
            stems.forEach { stem ->
                SELF_QUESTION_PREDICATES[stem]?.let { result.addAll(it) }
            }
        }
        return result
    }

    // ------------------------------------------------------------------
    // Derivation (maintenance/backfill path; stored in the v6 tables).
    // ------------------------------------------------------------------

    enum class EntityKind { PERSON, ORG, ROLE, PET }

    data class DerivedEntity(
        val name: String,
        val nameNormalized: String,
        val kind: EntityKind,
        val factIds: List<String>,
    )

    /**
     * Entities mentioned by ACTIVE RELATION facts. The fact's VALUE names
     * the entity («работаю у Иванова» → Иванов; predicate decides the
     * kind); facts sharing a normalized name merge into one entity with a
     * merged factId list (stable order by first sighting).
     */
    fun deriveEntities(facts: List<FactSnapshot>): List<DerivedEntity> {
        data class Acc(val name: String, val kind: EntityKind, val ids: MutableList<String>)

        val byKey = LinkedHashMap<String, Acc>()
        for (fact in facts) {
            val kind = if (isEligibleRelation(fact)) kindFor(fact.predicate) else null
            val name = fact.value.trim()
            val key = SearchTokenizer.normalize(name)
            if (kind != null && name.isNotEmpty() && key.isNotEmpty()) {
                byKey.getOrPut(key) { Acc(name, kind, mutableListOf()) }
                    .ids.add(fact.factId)
            }
        }
        return byKey.map { (key, acc) ->
            DerivedEntity(
                name = acc.name,
                nameNormalized = key,
                kind = acc.kind,
                factIds = acc.ids.toList(),
            )
        }
    }

    /** ACTIVE + RELATION — the only facts that name entities. */
    private fun isEligibleRelation(fact: FactSnapshot): Boolean =
        fact.status == FactStatus.ACTIVE && fact.category == FactCategory.RELATION

    private fun kindFor(predicate: String): EntityKind? = when (predicate) {
        "boss", "colleague", "spouse", "child", "parent", "friend" -> EntityKind.PERSON
        "works_at", "studies_at" -> EntityKind.ORG
        "works_as" -> EntityKind.ROLE
        "pet" -> EntityKind.PET
        else -> null
    }
}
