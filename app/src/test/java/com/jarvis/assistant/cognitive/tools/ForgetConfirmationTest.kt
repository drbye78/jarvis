package com.jarvis.assistant.cognitive.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §6.4 (forget hardening): the affirmation truth table. The
 * matcher must accept genuine RU/EN yeses and reject negations, questions and
 * non-answers — and must be TOKEN exact, never a substring match.
 */
class ForgetConfirmationTest {

    @Test
    fun `genuine affirmatives are accepted`() {
        val yes = listOf(
            "да", "ага", "угу", "верно", "точно", "именно",
            "подтверждаю", "подтверди", "подтвердите",
            "конечно", "разумеется", "хорошо", "ладно", "ок", "окей",
            "yes", "yeah", "yep", "sure", "confirm", "confirmed", "correct", "ok",
            "удали", "удалить", "забудь", "сотри", "delete", "forget", "remove",
            // multi-token confirmations
            "да пожалуйста", "удали его", "yes please", "да, это верно", "удали всё",
        )
        for (utterance in yes) {
            assertTrue("expected affirmative: «$utterance»", ForgetConfirmation.isAffirmative(utterance))
        }
    }

    @Test
    fun `negations are rejected`() {
        val no = listOf(
            "нет", "неа", "не", "не надо", "не удаляй", "не нужно",
            "да нет", "нет, не надо", "стоп", "отмена", "отставить",
            "no", "not", "never", "nope", "cancel", "stop", "no thanks", "don't",
        )
        for (utterance in no) {
            assertFalse("expected refusal: «$utterance»", ForgetConfirmation.isAffirmative(utterance))
        }
    }

    @Test
    fun `questions and unrelated statements are rejected`() {
        val others = listOf(
            "а что за фильм?", "какая погода?", "что ты умеешь",
            "расскажи о себе", "да?", "нет?", "как дела",
            "я не уверен но возможно", "почему ты спрашиваешь",
        )
        for (utterance in others) {
            assertFalse("expected non-answer: «$utterance»", ForgetConfirmation.isAffirmative(utterance))
        }
    }

    @Test
    fun `substring traps are not matched`() {
        // «нет» must not match inside a longer word, "yes" not inside
        // "yesterday", «да» not inside «удача».
        val traps = listOf("интернет", "нетология", "yesterday", "yes-man", "удача", "неделя")
        for (utterance in traps) {
            assertFalse(
                "substring must not match: «$utterance»",
                ForgetConfirmation.isAffirmative(utterance),
            )
        }
    }

    @Test
    fun `blank and over-long utterances are rejected`() {
        assertFalse(ForgetConfirmation.isAffirmative(null))
        assertFalse(ForgetConfirmation.isAffirmative(""))
        assertFalse(ForgetConfirmation.isAffirmative("   "))
        assertFalse(ForgetConfirmation.isAffirmative("да да да да да да да"))
    }

    @Test
    fun `one unexpected token makes it a statement, not a confirmation`() {
        // Normally-tolerant fillers cannot smuggle an unrelated clause in.
        assertFalse(ForgetConfirmation.isAffirmative("да расскажи мне про фильмы Тарковского"))
    }
}
