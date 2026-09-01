package com.jarvis.assistant.llm

/**
 * Upfront credential validation (Settings screen, "check as you type").
 *
 * The Settings panel probes the mandatory Sber credentials while the user is
 * still looking at the input fields, so a typo in Client ID / Client Secret is
 * caught in seconds instead of surfacing as a cryptic runtime failure on the
 * next voice command. Only Salute (ASR/TTS) and GigaChat (LLM) credentials are
 * mandatory; the Picovoice key is optional (needed only for the Porcupine
 * engine) and is validated by engine init, not probed here.
 *
 * [CredentialCheck] is the verdict of ONE probe against the Sber OAuth
 * endpoint — the same endpoint and request shape [TokenManager] uses for real
 * token fetches, minus token caching: a validation probe must not pollute the
 * token cache (a failed pair must not leave a token behind, and a valid pair
 * gets a fresh token when the pipeline actually runs).
 */
sealed interface CredentialCheck {

    /** The endpoint accepted the pair: 2xx with an `access_token`. */
    data object Valid : CredentialCheck

    /**
     * The endpoint rejected the pair (401/403/4xx). The credentials are wrong
     * or the client has no grant for the scope — user-fixable input.
     */
    data class Invalid(val httpCode: Int?) : CredentialCheck

    /**
     * No verdict possible: network failure, timeout, 5xx, rate limit or a
     * malformed response. NOT a statement about the credentials — the UI must
     * phrase this as "could not verify", never as "invalid".
     */
    data class Unverifiable(val reason: String) : CredentialCheck
}

/** Probes Sber OAuth credentials for the two mandatory services. */
interface CredentialValidator {
    /** Validate the SaluteSpeech pair (scope [OAuthScopes.SALUTE]). */
    suspend fun checkSalute(clientId: String, clientSecret: String): CredentialCheck

    /** Validate the GigaChat pair (scope [OAuthScopes.GIGACHAT]). */
    suspend fun checkGigaChat(clientId: String, clientSecret: String): CredentialCheck
}

/**
 * Sber OAuth scope values. Kept beside the validation domain because both
 * [CredentialValidator] implementations and tests need the exact literals;
 * [TokenManager] carries its own private copies (battle-tested, untouched).
 */
object OAuthScopes {
    const val GIGACHAT = "GIGACHAT_API_PERS"
    const val SALUTE = "SALUTE_SPEECH_PERS"
}
