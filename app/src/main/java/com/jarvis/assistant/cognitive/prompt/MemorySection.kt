package com.jarvis.assistant.cognitive.prompt

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.recall.ScoredFact
import com.jarvis.assistant.tools.ToolStrings

/**
 * COGNITIVE_PLAN §7.1: the deterministic `MemorySection` — everything the
 * ranked facts become inside the system prompt. Pure Kotlin + the
 * ToolStrings seam, fixture-tested.
 *
 * Budget rule (plan §7.1/§12.2): the WHOLE section is ≤ [SECTION_BUDGET]
 * chars; the profile line ≤ [PROFILE_BUDGET]. When over budget the
 * LOWEST-RANKED bullets are dropped whole — never truncated mid-line — and
 * the output is deterministic for a given (facts, strings) pair. Snapshot
 * tests lock both properties and the disabled-memory byte-identity.
 */
object MemorySectionRenderer {

    const val SECTION_BUDGET = 1200
    const val PROFILE_BUDGET = 300

    /** One ranked gather result rendered for the prompt. */
    data class Rendered(
        val text: String,
        val profileChars: Int,
        val bullets: List<String>,
        val droppedBullets: Int,
    )

    fun render(data: MemorySectionData, strings: ToolStrings): String =
        renderDetailed(data, strings).text

    fun renderDetailed(data: MemorySectionData, strings: ToolStrings): Rendered {
        if (data.isEmpty) return Rendered("", 0, emptyList(), 0)

        val headerLine = strings.memoryContextHeader
        val wrapper = SECTION_OPEN.length + SECTION_CLOSE.length + 4 // newlines

        // 1) Profile line: the identity anchor, highest priority.
        val profile = buildProfile(data.profileFacts, strings)
        var budget = SECTION_BUDGET - wrapper - headerLine.length

        val profileLine = if (profile.length <= budget) {
            budget -= profile.length + 1
            profile
        } else {
            truncateAtWord(profile, (budget - 1).coerceAtLeast(0)).also {
                budget -= it.length + 1
            }
        }

        // 2) Bullets in ranked order; lowest-ranked dropped whole when over.
        val kept = mutableListOf<String>()
        var dropped = 0
        for (bullet in data.bullets) {
            val cost = bullet.length + 1
            if (budget - cost < 0) {
                dropped += 1 + (data.bullets.size - kept.size - 1)
                break
            }
            budget -= cost
            kept.add(bullet)
        }

        val sb = StringBuilder()
        sb.append(SECTION_OPEN)
        sb.append(headerLine)
        if (profileLine.isNotEmpty()) {
            sb.append('\n').append(profileLine)
        }
        kept.forEach { sb.append('\n').append(it) }
        sb.append(SECTION_CLOSE)
        return Rendered(sb.toString(), profileLine.length, kept.toList(), dropped)
    }

    /** «Пользователь: зовут Алексей; жена Маша; любит фильмы Тарковского.» */
    private fun buildProfile(profileFacts: List<String>, strings: ToolStrings): String {
        if (profileFacts.isEmpty()) return ""
        val body = profileFacts.joinToString("; ")
        var line = strings.memoryProfilePrefix + body
        if (line.length > PROFILE_BUDGET) {
            line = truncateAtWord(line, PROFILE_BUDGET)
        }
        return line
    }

    /** Word-boundary truncation with an ellipsis — never mid-line garbage. */
    internal fun truncateAtWord(text: String, budget: Int): String {
        if (text.length <= budget) return text
        if (budget <= 1) return ""
        val cut = text.lastIndexOf(' ', budget - 1)
        val at = if (cut > budget / 2) cut else budget - 1
        return text.take(at).trimEnd() + "…"
    }

    private const val SECTION_OPEN = "<memory-context>\n"
    private const val SECTION_CLOSE = "\n</memory-context>"
}

/**
 * Gather output consumed by the [com.jarvis.assistant.session.PromptComposer].
 * Rendering happens in the coordinator (it owns ranking + strings); the
 * composer only decides placement, keeping prompt assembly bytecode-stable.
 */
data class MemorySectionData(
    /** Identity-anchor facts as label:value strings (already rendered). */
    val profileFacts: List<String>,

    /** Ranked fact bullets, highest score first (already rendered). */
    val bullets: List<String>,

    /** True when the 40 ms budget was missed / the DB errored (counter bump). */
    val degraded: Boolean,
) {
    val isEmpty: Boolean get() = profileFacts.isEmpty() && bullets.isEmpty()

    companion object {
        val EMPTY = MemorySectionData(emptyList(), emptyList(), degraded = false)
    }
}

/**
 * Shared memory render helpers for the coordinator: fact → profile entry and
 * fact → bullet, with the RU predicate labels (prompt-internal vocabulary —
 * the same policy that keeps the rest of the system prompt Russian; wrapper
 * strings still flow through [ToolStrings] for parity).
 */
object FactPhrasing {

    private val PREDICATE_LABELS = mapOf(
        "name" to "зовут",
        "birthday" to "день рождения",
        "age" to "возраст",
        "lives_in" to "живёт в",
        "likes" to "любит",
        "dislikes" to "не любит",
        "favorite" to "любимое",
        "works_at" to "работает в",
        "works_as" to "работает",
        "studies_at" to "учится в",
        "boss" to "начальник",
        "colleague" to "коллега",
        "spouse" to "супруг(а)",
        "child" to "ребёнок",
        "parent" to "родитель",
        "friend" to "друг",
        "pet" to "питомец",
        "owns" to "владеет",
        "routine" to "обычно",
        "goal" to "цель",
        "health" to "здоровье",
    )

    /** «зовут Алексей» / «начальник: Олег» — no label when unknown. */
    fun phrase(fact: FactSnapshot): String {
        val label = PREDICATE_LABELS[fact.predicate]
        val body = if (label.isNullOrBlank()) fact.value else "$label ${fact.value}"
        return if (fact.subject == "user") body else "${fact.subject}: $body"
    }

    /** Bullet with confidence + sensitivity + contest marks (plan §7.1). */
    fun bullet(fact: FactSnapshot, strings: ToolStrings): String {
        val marks = mutableListOf<String>()
        marks.add(
            when {
                fact.confidence >= 0.8f -> strings.memoryConfidenceHigh
                fact.confidence >= 0.5f -> strings.memoryConfidenceMedium
                else -> strings.memoryConfidenceLow
            },
        )
        if (fact.sensitive) marks.add(strings.memorySensitiveMark)
        if (fact.contested) marks.add(strings.memoryContestedNote)
        return "— ${phrase(fact)} (${marks.joinToString(", ")})"
    }

    /** Profile entries come only from the anchor categories (plan §7.1). */
    fun isProfileCategory(category: FactCategory): Boolean =
        category == FactCategory.IDENTITY || category == FactCategory.RELATION
}

/** Convenience for the coordinator: render the whole gather result. */
fun renderMemorySection(
    ranked: List<ScoredFact>,
    degraded: Boolean,
    strings: ToolStrings,
): MemorySectionData {
    // Profile carries the identity anchor; every other ranked fact becomes a
    // bullet. A fact never appears in both places (no duplication in the
    // prompt budget).
    val profileFacts = ranked
        .filter { FactPhrasing.isProfileCategory(it.fact.category) }
        .map { FactPhrasing.phrase(it.fact) }
    val bullets = ranked
        .filterNot { FactPhrasing.isProfileCategory(it.fact.category) }
        .map { FactPhrasing.bullet(it.fact, strings) }
    return MemorySectionData(profileFacts, bullets, degraded)
}
