package com.jarvis.assistant.util

/**
 * Seam over secret storage (API keys, OAuth tokens).
 *
 * Replaces the deprecated `androidx.security:security-crypto`
 * (EncryptedSharedPreferences) foundation with a thin interface:
 *
 * - Production wires [KeystoreVault] — every value is AES-256-GCM encrypted
 *   under an AndroidKeyStore master key; the ciphertext lands in a plain
 *   SharedPreferences file (an attacker with the prefs file but without the
 *   device keystore gets nothing).
 * - JVM tests wire [InMemoryVault] — no Android runtime required.
 *
 * Values are opaque strings; the vault never logs them (callers are
 * responsible for sanitized error messages, a rule this codebase already
 * enforces for OAuth bodies).
 */
interface SecretVault {
    fun getString(key: String): String?

    fun putString(key: String, value: String)

    fun remove(key: String)

    /** Removes every secret. Used by the "clear credentials" path. */
    fun clear()

    /** True when the key exists AND decrypts to a non-blank value. */
    fun hasNonBlank(key: String): Boolean = !getString(key).isNullOrBlank()

    companion object {
        // Canonical key names shared by [CredentialsStore], [TokenManager]
        // and the OpenAI-compatible API key slot. Single source of truth so
        // no call site invents its own spelling (the old code had
        // "openai_api_key" duplicated between AppGraph and AppPrefs).
        const val KEY_PICOVOICE = "picovoice_key"
        const val KEY_SALUTE_ID = "salute_client_id"
        const val KEY_SALUTE_SECRET = "salute_client_secret"
        const val KEY_GIGA_ID = "gigachat_client_id"
        const val KEY_GIGA_SECRET = "gigachat_client_secret"
        const val KEY_OPENAI_API_KEY = "openai_api_key"
        const val KEY_GIGACHAT_TOKEN = "gigachat_token"
        const val KEY_GIGACHAT_EXPIRY = "gigachat_token_expiry"
        const val KEY_SALUTE_TOKEN = "salute_token"
        const val KEY_SALUTE_EXPIRY = "salute_token_expiry"
    }
}

/** Plain in-memory vault for JVM tests and non-persistent paths. */
class InMemoryVault : SecretVault {
    private val map = HashMap<String, String>()

    override fun getString(key: String): String? = map[key]

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }

    override fun clear() {
        map.clear()
    }
}
