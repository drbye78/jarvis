package com.jarvis.assistant.ui

import androidx.annotation.StringRes
import com.jarvis.assistant.R

/**
 * Pure field validation for the Settings credential forms (U7).
 *
 * Errors used to be delivered as a Toast, which is the wrong surface for a
 * problem with a specific input: it names no field, it disappears on its own,
 * and it cannot be read by a screen reader in the context of the field that
 * caused it. This object decides WHAT is wrong and WHICH field owns it; the
 * caller attaches the message to that field's `TextInputLayout`.
 *
 * Pure JVM on purpose — the decisions are testable without Robolectric, and
 * the Android rendering lives in `FieldErrorRenderer`.
 */
object FieldValidation {

    /** A settings input that can carry an error. */
    enum class Field {
        OPENAI_BASE_URL,
        OPENAI_API_KEY,
        SALUTE_ID,
        SALUTE_SECRET,
        GIGACHAT_ID,
        GIGACHAT_SECRET,
    }

    /** One validation failure, addressed to the field the user must fix. */
    data class FieldError(
        val field: Field,
        @StringRes val messageRes: Int,
    )

    /**
     * OpenAI-compatible provider block. The base URL is mandatory. The API
     * key is mandatory too once a base URL is present — an endpoint without
     * a key fails at the first turn, so the message ("Укажите API-ключ")
     * belongs on the field rather than surfacing later as a failed request.
     */
    fun validateLlmProvider(baseUrl: String, apiKey: String): List<FieldError> {
        val trimmedUrl = baseUrl.trim()
        val errors = mutableListOf<FieldError>()
        if (trimmedUrl.isEmpty()) {
            errors += FieldError(Field.OPENAI_BASE_URL, R.string.error_base_url)
        } else if (apiKey.trim().isEmpty()) {
            errors += FieldError(Field.OPENAI_API_KEY, R.string.error_api_key)
        }
        return errors
    }

    /**
     * The two mandatory OAuth credential pairs (Sber Salute, GigaChat).
     *
     * A fully-empty pair is "not configured yet", not an error — saving stays
     * local-first and a user may fill them later. A HALF-filled pair is an
     * error: an id without its secret (or the reverse) can never
     * authenticate, and the message is attached to the missing half, which is
     * the field the user has to act on.
     */
    fun validateCredentials(
        saluteId: String,
        saluteSecret: String,
        gigaChatId: String,
        gigaChatSecret: String,
    ): List<FieldError> = buildList {
        addAll(halfFilledPair(saluteId, saluteSecret, Field.SALUTE_ID, Field.SALUTE_SECRET))
        addAll(
            halfFilledPair(
                gigaChatId,
                gigaChatSecret,
                Field.GIGACHAT_ID,
                Field.GIGACHAT_SECRET,
            ),
        )
    }

    private fun halfFilledPair(
        id: String,
        secret: String,
        idField: Field,
        secretField: Field,
    ): List<FieldError> {
        val hasId = id.trim().isNotEmpty()
        val hasSecret = secret.trim().isNotEmpty()
        if (hasId == hasSecret) return emptyList()
        return listOf(
            if (hasId) {
                FieldError(secretField, R.string.error_credential_secret_missing)
            } else {
                FieldError(idField, R.string.error_credential_id_missing)
            },
        )
    }
}
