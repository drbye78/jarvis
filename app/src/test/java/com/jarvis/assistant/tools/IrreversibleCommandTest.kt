package com.jarvis.assistant.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Truth table for [IrreversibleCommand] — the utterance-derived gate for
 * `cancelAlarm`/`cancelTimer`. Whole-token matching, negation veto, question
 * veto, and an explicit fail-closed default.
 */
class IrreversibleCommandTest {

    @Test
    fun `recognizes explicit removal commands`() {
        listOf(
            "отмени будильник",
            "Отмени, будильник!",
            "отмените таймер",
            "отменить будильник на семь утра",
            "удали будильник",
            "убери таймер",
            "сотри будильник",
            "сбрось таймер",
            "Отмена будильника",
            "cancel the alarm",
            "delete timer",
            "remove the alarm",
            "dismiss this alarm",
        ).forEach { utterance ->
            assertTrue("must be a removal command: «$utterance»", IrreversibleCommand.isCommand(utterance))
        }
    }

    @Test
    fun `a negation vetoes the whole utterance`() {
        listOf(
            "не отменяй будильник",
            "нет, не удаляй таймер",
            "ни в коем случае не убирай",
            "don't cancel the alarm",
            "no, do not delete it",
            "never remove the timer",
        ).forEach { utterance ->
            assertFalse("a negated command must not match: «$utterance»", IrreversibleCommand.isCommand(utterance))
        }
    }

    @Test
    fun `a question is never a command`() {
        listOf(
            "можешь отменить будильник?",
            "отменить будильник?",
            "can you cancel the alarm?",
        ).forEach { utterance ->
            assertFalse("a question must not match: «$utterance»", IrreversibleCommand.isCommand(utterance))
        }
    }

    @Test
    fun `an unrelated turn does not set the flag`() {
        listOf(
            "какая погода",
            "включи музыку",
            "поставь будильник на семь",
            "расскажи анекдот",
            "спасибо",
            "what time is it",
        ).forEach { utterance ->
            assertFalse("an unrelated turn must not match: «$utterance»", IrreversibleCommand.isCommand(utterance))
        }
    }

    @Test
    fun `substring matches are rejected`() {
        // «отмени» as a SUBSTRING of a longer unrelated token must not match —
        // matching is whole-token, never `contains`.
        listOf(
            "суперотмени",
            "автоотмена",
            "отменитель",
            "cancellation",
            "cancelled",
            "deleteion",
        ).forEach { utterance ->
            assertFalse("substring must not match: «$utterance»", IrreversibleCommand.isCommand(utterance))
        }
    }

    @Test
    fun `forget verbs are deliberately outside this vocabulary`() {
        // forget_fact keeps its OWN confirmation gate; this matcher must not
        // stand in for it (a bare «да» must never authorize a cancellation).
        assertFalse(IrreversibleCommand.isCommand("забудь это"))
    }

    @Test
    fun `null and blank fail closed`() {
        assertFalse(IrreversibleCommand.isCommand(null))
        assertFalse(IrreversibleCommand.isCommand(""))
        assertFalse(IrreversibleCommand.isCommand("   "))
        assertFalse(IrreversibleCommand.isCommand("?!"))
    }
}
