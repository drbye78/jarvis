package com.jarvis.assistant.tools

import com.jarvis.assistant.cognitive.recall.SearchTokenizer

/**
 * A small, pure, deterministic matcher answering ONE question: does the
 * user's OWN utterance command an irreversible removal/cancellation?
 *
 * Why this exists: the LLM can be steered (prompt injection in a web result, a
 * remembered fact, a tool payload) into calling `cancelAlarm`/`cancelTimer`.
 * The old boundary permitted every IRREVERSIBLE tool on every voice turn —
 * `TurnAuthorization.voice()` hardcoded `explicitUserCommand = true` — so the
 * gate was decorative. This matcher is what derives the flag from the turn's
 * FINAL ASR text, and it is deliberately NOT model-supplied: the utterance is
 * produced by ASR, never by the LLM.
 *
 * Rule set (all must hold):
 *  1. non-null, non-blank;
 *  2. NOT a question — a literal `?` anywhere vetoes (asking "можешь отменить?"
 *     is not a command);
 *  3. contains at least one WHOLE-TOKEN removal verb ([REMOVAL_VERBS]);
 *  4. contains NO whole-token negation ([NEGATIONS]) — «не отменяй» vetoes.
 *
 * Matching is TOKEN EXACT, never substring: the utterance is normalized with
 * [SearchTokenizer.normalize] (lowercase, ё→е, punctuation → space) and split
 * on spaces, exactly as `ForgetConfirmation` does. A token must EQUAL a verb —
 * «отмени» inside «отменитель» or "cancel" inside "cancelled" cannot match
 * because they are single unrelated tokens. The vocabulary is deliberately
 * tiny and explicit; unknown forms fail CLOSED (no match).
 *
 * Scope: this flag is the gate for the OTHER irreversible tools
 * (`cancelAlarm`/`cancelTimer`). `forget_fact` keeps its own independent
 * confirmation gate (`CognitiveCoordinator` + `ForgetConfirmation`) and is
 * deliberately exempt from this matcher — see [ToolAuthorization]. Building
 * the flag from a bare «да» just to carry the forget confirmation would let an
 * unrelated affirmative authorize an alarm cancellation, so the two gates
 * stay separate.
 *
 * Action awareness: the vocabulary is removal/cancellation only, so an
 * unrelated turn («какая погода», «включи музыку», «поставь будильник»)
 * never sets the flag. It does NOT distinguish an alarm from a timer: any
 * genuine removal command authorizes the removal class for that turn, which
 * is the honest granularity of a single utterance-derived boolean.
 */
object IrreversibleCommand {

    /**
     * Whole-token negations. Presence of ANY of these vetoes the utterance,
     * even alongside a removal verb («не отменяй будильник», "no don't
     * cancel"). Apostrophes split tokens, so "don't" contributes "don".
     */
    private val NEGATIONS = setOf(
        "не", "нет", "неа", "ни", "нельзя", "no", "not", "never", "nope",
        "don", "dont", "doesn", "didn", "without",
    )

    /**
     * Imperative/verb forms that command a removal or cancellation. Kept to
     * explicit whole tokens (no stemming): «отменить» and «отмени» are both
     * listed because normalization leaves them distinct.
     */
    private val REMOVAL_VERBS = setOf(
        "отмени", "отмените", "отменить", "отменяй", "отменяйте", "отмена", "отмену",
        "удали", "удалите", "удалить", "удаляй", "удаляйте",
        "убери", "уберите", "убрать", "убирай",
        "сотри", "сотрите", "стереть",
        "снеси", "снесите", "снести",
        "сбрось", "сбросьте", "сбросить",
        "cancel", "remove", "delete", "erase", "dismiss",
    )

    /**
     * True only when the utterance itself commands removal/cancellation.
     * Null/blank, a question, a negation, or a turn with no removal verb
     * returns false — the conservative default is refusal (fail closed).
     */
    fun isCommand(utterance: String?): Boolean {
        if (utterance.isNullOrBlank()) return false
        if (utterance.contains('?')) return false // a question is not a command
        val tokens = tokenize(utterance)
        return tokens.isNotEmpty() &&
            tokens.none { it in NEGATIONS } &&
            tokens.any { it in REMOVAL_VERBS }
    }

    /** Normalized whole-token split; empty normalization yields an empty list. */
    private fun tokenize(utterance: String): List<String> =
        SearchTokenizer.normalize(utterance).split(' ').filter { it.isNotEmpty() }
}
