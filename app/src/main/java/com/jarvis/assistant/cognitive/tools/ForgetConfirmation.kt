package com.jarvis.assistant.cognitive.tools

import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * A small, explicit, pure matcher for an EXPLICIT AFFIRMATIVE in the
 * confirming turn.
 *
 * Why this exists: the turn-provenance gate alone only required the confirming
 * utterance to be strictly LATER than the candidate listing. Any later
 * utterance satisfied it — «нет», an unrelated question, or a turn the user
 * never intended as confirmation — so a persisted injection degraded to a
 * one-extra-utterance exploit. The gate now additionally requires the
 * immediately-next user turn to be a genuine "yes".
 *
 * Matching is TOKEN EXACT, never substring: the utterance is normalized with
 * the same [SearchTokenizer.normalize] the rest of the cognitive lane uses
 * (lowercase, ё→е, punctuation → space) and split on spaces. A token must
 * equal a known word — «нет» inside «интернет» or "yes" inside "yesterday"
 * cannot match because they are single unrelated tokens. The vocabulary is
 * deliberately tiny and explicit.
 *
 * A question is rejected outright (a literal `?` anywhere), and any negation
 * token vetoes the whole utterance («не удаляй», «да нет»).
 */
object ForgetConfirmation {

    /** Longer utterances are statements, not confirmations. */
    private const val MAX_TOKENS = 6

    /**
     * Whole-token negations. Presence of ANY of these vetoes the utterance,
     * even alongside an affirmative («да нет», «не надо», «no thanks»).
     */
    private val NEGATIONS = setOf(
        "нет", "не", "неа", "ни", "нельзя", "отмена", "отставить", "стоп",
        "no", "not", "never", "nope", "don", "doesn", "didn", "cancel", "stop",
    )

    /**
     * Tokens that BY THEMSELVES affirm (consent): at least one must be present
     * for the utterance to count.
     */
    private val AFFIRMATIVE_TOKENS = setOf(
        "да", "ага", "угу", "верно", "точно", "именно",
        "подтверждаю", "подтверди", "подтвердить", "подтвердите", "подтверждение",
        "конечно", "разумеется", "давай", "ок", "окей", "хорошо", "ладно",
        "yes", "yeah", "yep", "yup", "sure", "confirm", "confirmed",
        "correct", "right", "ok", "okay", "absolutely", "definitely",
        "affirmative",
    )

    /**
     * Imperative "do it" verbs: an explicit delete command is consent too
     * («удали», "delete").
     */
    private val DELETE_VERBS = setOf(
        "удали", "удалить", "удаляй", "удалите",
        "убери", "убрать", "уберите", "забудь", "забыть", "забудьте",
        "сотри", "стереть", "сотрите", "очисти", "очистить", "очистите",
        "delete", "remove", "forget", "erase",
    )

    /**
     * Harmless filler/object words that may accompany a confirmation
     * («да, пожалуйста», «удали его», "yes please"). Filler alone never
     * affirms — [AFFIRMATIVE_TOKENS] or [DELETE_VERBS] must still be present.
     */
    private val FILLER_TOKENS = setOf(
        "пожалуйста", "это", "его", "ее", "их", "все", "мне", "нам",
        "please", "it", "them", "this", "that", "the", "all", "me", "my",
    )

    private val CONFIRMING = AFFIRMATIVE_TOKENS + DELETE_VERBS
    private val ALLOWED = CONFIRMING + FILLER_TOKENS

    /**
     * True only for an explicit affirmative. Null/blank, a question, a
     * negation, a long statement, or any out-of-vocabulary token returns
     * false — the conservative default is refusal.
     */
    fun isAffirmative(utterance: String?): Boolean {
        if (utterance.isNullOrBlank()) return false
        if (utterance.contains('?')) return false // a question is not a confirmation
        val tokens = tokenize(utterance)
        if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return false
        if (tokens.any { it in NEGATIONS }) return false
        // Every token must be part of the confirmation vocabulary; one
        // unexpected token makes this a statement, not a confirmation.
        val allTokensAllowed = tokens.all { it in ALLOWED }
        return allTokensAllowed && tokens.any { it in CONFIRMING }
    }

    /** Normalized whole-token split; empty normalization yields an empty list. */
    private fun tokenize(utterance: String): List<String> =
        SearchTokenizer.normalize(utterance).split(' ').filter { it.isNotEmpty() }
}
