package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * COGNITIVE_PLAN Appendix A + §6.2: the extraction LLM contract — the exact
 * system prompt (RU, temperature 0), the user-content builder for a batch,
 * and the predicate→category map with sensitive-topic patterns used by the
 * local validator. One file owns the whole contract so a model change is a
 * one-place diff (the eval harness re-runs against the same contract).
 */
object ExtractionContract {

    /**
     * Appendix A, verbatim requirements: STRICT JSON only, no commentary,
     * evidence must occur verbatim in the utterance, empty result is
     * `{"facts":[]}`.
     *
     * Subject wording is deliberately USER-ONLY: it matches the enforced
     * [SUBJECTS] whitelist, so the model is never invited to emit the
     * free-text subject that [ExtractionParser] would drop. Named people are
     * encoded as a RELATION predicate with the name in `value`.
     */
    val SYSTEM_PROMPT = """
        Ты — модуль извлечения фактов голосового ассистента. На вход даются реплики пользователя с номерами.
        Верни СТРОГО JSON без пояснений: только фактически устойчивые сведения о пользователе; не выдумывай; каждое поле evidence должно дословно встречаться в реплике; если фактов нет — верни {"facts":[]}.
        Поле subject ВСЕГДА равно "user", других значений не существует. Сведения о названных людях выражай отношением (spouse, child, parent, friend, colleague, boss, pet), а имя человека укажи в value: {"subject":"user","predicate":"spouse","value":"Аня"}.
        Формат: {"facts":[{"subject":"user","predicate":"name","value":"Алексей","confidence":0.95,"evidence":"меня зовут Алексей","messageId":42}]}
        Возможные predicate: name, birthday, age, lives_in, likes, dislikes, favorite, works_at, works_as, studies_at, boss, colleague, spouse, child, parent, friend, pet, owns, routine, goal, health, other.
    """.trimIndent()

    /** Numbered utterances for one batch: "42: меня зовут Алексей". */
    fun buildUserContent(batch: List<Pair<Long, String>>): String =
        batch.joinToString("\n") { (id, text) -> "$id: $text" }

    /**
     * Predicate whitelist → category (plan §6.2: "unknown predicates →
     * OTHER"). Keys are the exact strings the prompt advertises.
     */
    val PREDICATE_CATEGORIES: Map<String, FactCategory> = mapOf(
        "name" to FactCategory.IDENTITY,
        "birthday" to FactCategory.IDENTITY,
        "age" to FactCategory.IDENTITY,
        "lives_in" to FactCategory.IDENTITY,
        "likes" to FactCategory.PREFERENCE,
        "dislikes" to FactCategory.PREFERENCE,
        "favorite" to FactCategory.PREFERENCE,
        "works_at" to FactCategory.RELATION,
        "works_as" to FactCategory.RELATION,
        "studies_at" to FactCategory.RELATION,
        "boss" to FactCategory.RELATION,
        "colleague" to FactCategory.RELATION,
        "spouse" to FactCategory.RELATION,
        "child" to FactCategory.RELATION,
        "parent" to FactCategory.RELATION,
        "friend" to FactCategory.RELATION,
        "pet" to FactCategory.RELATION,
        "owns" to FactCategory.POSSESSION,
        "routine" to FactCategory.ROUTINE,
        "goal" to FactCategory.GOAL,
        "health" to FactCategory.HEALTH,
        "other" to FactCategory.OTHER,
    )

    /**
     * Subject whitelist (audit MEDIUM): the contract advertises exactly ONE
     * subject — `"user"` (see the format line in [SYSTEM_PROMPT]). Facts about
     * other people/things are encoded the contract's way, through the RELATION
     * predicates ([PREDICATE_CATEGORIES]: spouse/child/parent/friend/
     * colleague/boss/pet) with subject `user`, NOT through a free-text subject
     * name. An unanchored subject is attacker-influenceable: it is normalized,
     * stored and rendered verbatim into the per-turn `<memory-context>` prompt
     * (`FactPhrasing.phrase`), exactly the surface the system prompt warns
     * about — so it is validated like `predicate`: anything outside this set is
     * DROPPED (never coerced into a valid-looking row).
     *
     * Relations with NO sanctioned predicate (e.g. a sibling — there is no
     * `sibling`/`brother`/`sister`) are encoded as `predicate: "other"` with the
     * relation and the person kept in `value` (e.g. «сестра Катя, любит суши»).
     * Inventing a predicate, or reintroducing a free-text subject, is NOT an
     * option: whichever is added must land in [PREDICATE_CATEGORIES], in
     * [SYSTEM_PROMPT] and in the `remember_fact` category hint together.
     */
    val SUBJECTS: Set<String> = setOf(DEFAULT_SUBJECT)

    /** Documented default subject for a blank/absent `subject` field. */
    const val DEFAULT_SUBJECT = "user"

    /**
     * Length cap (defence in depth for the normalize/store/render path): even
     * a value that normalizes onto the whitelist is bounded, and an injected
     * payload smuggled into `subject` can never grow past this.
     */
    const val MAX_SUBJECT_LENGTH = 32

    /**
     * Resolve a raw model-supplied `subject` to a sanctioned subject, or null
     * when it is outside [SUBJECTS] (the caller then drops the row). Blank or
     * absent defaults to [DEFAULT_SUBJECT]; otherwise the value is normalized
     * (case/punctuation folded) and must match the whitelist within
     * [MAX_SUBJECT_LENGTH].
     */
    fun sanitizeSubject(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return DEFAULT_SUBJECT
        val normalized = SearchTokenizer.normalize(trimmed)
        return normalized.takeIf { it.length <= MAX_SUBJECT_LENGTH && it in SUBJECTS }
    }

    /**
     * Sensitive-topic substrings (plan §6.2: HEALTH / politics / religion
     * patterns mark `sensitive=true`; §12.4-2: visible-but-marked, prompt
     * injection governed by the user switch).
     */
    private val SENSITIVE_PATTERNS = listOf(
        "болезн", "болен", "болит", "таблетк", "лекарств", "диагноз",
        "давлени", "температур", "аллерг", "терапи", "врач", "доктор",
        "беремен", "инвалид", "операци",
        "голосова", "выборы", "парти", "политик", "президент",
        "религи", "веру", "церков", "пост", "мечет", "молитв", "бог",
    )

    /** Category + sensitivity resolution for a validated predicate/value. */
    fun categorize(predicateRaw: String?, value: String): Pair<FactCategory, Boolean> {
        val predicate = predicateRaw?.trim()?.lowercase(java.util.Locale.ROOT) ?: ""
        val category = PREDICATE_CATEGORIES[predicate] ?: FactCategory.OTHER
        val lowered = value.lowercase(java.util.Locale.ROOT)
        val sensitive = category == FactCategory.HEALTH ||
            SENSITIVE_PATTERNS.any { lowered.contains(it) }
        return category to sensitive
    }

    /** chatOnce request parameters for extraction (plan §6.2: temp 0). */
    const val TEMPERATURE = 0.0
    const val MAX_TOKENS = 1024
}
