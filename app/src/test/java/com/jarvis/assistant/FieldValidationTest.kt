package com.jarvis.assistant

import com.jarvis.assistant.ui.FieldValidation
import com.jarvis.assistant.ui.FieldValidation.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure validation for the Settings credential forms (U7). The Android
 * rendering (`FieldErrorRenderer`) is glue; the decisions below are what the
 * inline-error fix actually depends on.
 */
class FieldValidationTest {

    // ---- OI-compatible provider block ----

    @Test
    fun `base url is mandatory`() {
        val errors = FieldValidation.validateLlmProvider(baseUrl = "", apiKey = "k")
        assertEquals(1, errors.size)
        assertEquals(Field.OPENAI_BASE_URL, errors.single().field)
    }

    @Test
    fun `api key is mandatory once a base url is present`() {
        val errors = FieldValidation.validateLlmProvider(baseUrl = "https://x", apiKey = "  ")
        assertEquals(1, errors.size)
        assertEquals(Field.OPENAI_API_KEY, errors.single().field)
    }

    @Test
    fun `a complete provider block is accepted`() {
        assertTrue(
            FieldValidation.validateLlmProvider("https://x", "key").isEmpty(),
        )
    }

    @Test
    fun `whitespace-only base url counts as missing`() {
        val errors = FieldValidation.validateLlmProvider(baseUrl = "   ", apiKey = "k")
        assertEquals(Field.OPENAI_BASE_URL, errors.single().field)
    }

    // ---- mandatory OAuth pairs ----

    @Test
    fun `fully empty pairs are not an error`() {
        // "Not configured yet" is a legitimate state: saving stays local-first.
        assertTrue(
            FieldValidation.validateCredentials("", "", "", "").isEmpty(),
        )
    }

    @Test
    fun `id without secret blames the secret field`() {
        val errors = FieldValidation.validateCredentials("id", "", "", "")
        assertEquals(1, errors.size)
        assertEquals(Field.SALUTE_SECRET, errors.single().field)
    }

    @Test
    fun `secret without id blames the id field`() {
        val errors = FieldValidation.validateCredentials("", "sec", "", "")
        assertEquals(1, errors.size)
        assertEquals(Field.SALUTE_ID, errors.single().field)
    }

    @Test
    fun `each pair is validated independently`() {
        // Salute complete, GigaChat half-filled: only the GigaChat half fails.
        val errors = FieldValidation.validateCredentials(
            saluteId = "s-id",
            saluteSecret = "s-sec",
            gigaChatId = "g-id",
            gigaChatSecret = "",
        )
        assertEquals(1, errors.size)
        assertEquals(Field.GIGACHAT_SECRET, errors.single().field)
    }

    @Test
    fun `both pairs half-filled report two errors`() {
        val errors = FieldValidation.validateCredentials(
            saluteId = "",
            saluteSecret = "s-sec",
            gigaChatId = "g-id",
            gigaChatSecret = "",
        )
        assertEquals(
            listOf(Field.SALUTE_ID, Field.GIGACHAT_SECRET),
            errors.map { it.field },
        )
    }

    @Test
    fun `whitespace does not count as filled`() {
        val errors = FieldValidation.validateCredentials("  ", "  ", "", "")
        assertTrue(errors.isEmpty())
    }
}
