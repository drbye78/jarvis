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

        /**
         * Yandex Cloud service-account API key. One key drives BOTH SpeechKit
         * v3 (see [com.jarvis.assistant.speech.yandexApiKeyStub]) and the
         * Yandex AI Studio LLM (`Authorization: Api-Key`, no OAuth) — the LLM
         * needs AI Studio access on the key, not just SpeechKit. Unlike the
         * Sber OAuth pair this key never expires and needs no configured
         * folder id (SpeechKit implies the SA folder; the LLM discovers it via
         * `GET /v1/models`).
         */
        const val KEY_YANDEX_API_KEY = "yandex_api_key"

        /**
         * Yandex MapKit Mobile SDK key (UUID shape), sent by the SDK as the
         * `X-YMapKit-Api-Key` header. This is NOT [KEY_YANDEX_API_KEY]: that
         * one is a Cloud service-account API key for SpeechKit / AI Studio,
         * while MapKit needs a separate Maps API key. The two are not
         * interchangeable, so they must never share a slot.
         */
        const val KEY_MAPKIT_API_KEY = "mapkit_api_key"

        /**
         * Optional HTTP/SOCKS proxy for the Open-Meteo API ONLY (forecast +
         * geocoding). May embed `user:pass@` credentials, so it lives in the
         * vault rather than [com.jarvis.assistant.util.AppPrefs]. Blank =
         * direct connection. Applied LIVE on the next weather request; the
         * shared LLM/TTS/gRPC client is never proxied.
         */
        const val KEY_OPEN_METEO_PROXY = "open_meteo_proxy"

        /**
         * Prefix for one MCP server's auth secret; the full key is
         * `mcp_secret_<serverId>` (see [mcpSecretKey]). Keying by the server's
         * stable id keeps the config blob secret-free and lets a deleted
         * server's secret be cleared independently.
         */
        const val KEY_MCP_SECRET_PREFIX = "mcp_secret_"

        /** The vault key holding [serverId]'s MCP auth secret. */
        fun mcpSecretKey(serverId: String): String = KEY_MCP_SECRET_PREFIX + serverId

        /**
         * Prefix for one smart-home integration's secret; the full key is
         * `home_secret_<providerId>_<field>` (see [homeSecretKey]). The
         * provider config's stable id plus a field name (e.g. `token`) keep the
         * config blob secret-free and let a removed integration's secret be
         * cleared independently.
         */
        const val KEY_HOME_SECRET_PREFIX = "home_secret_"

        /** The vault key holding [providerId]'s [field] secret. */
        fun homeSecretKey(providerId: String, field: String): String =
            KEY_HOME_SECRET_PREFIX + providerId + "_" + field

        /**
         * R13 §14 management password. Stored in the vault so the owner can
         * re-show it on demand; it is device-bound (never leaves the Keystore
         * vault) and is NOT an [AppPrefs] property — it has no typed accessor,
         * so it is read/written through the vault directly. It is a fixed key
         * (unlike the argument-keyed MCP secrets): one management password per
         * device.
         */
        const val KEY_MANAGEMENT_PASSWORD = "management_password"

        /**
         * R13 §14.2 TLS persistence: the Base64 PKCS#12 keystore blob (which
         * holds the management server's PRIVATE KEY) and the password that
         * unlocks it. Two fixed keys — one cert per device — written/read only
         * by [com.jarvis.assistant.manage.ManagementTlsStore], so the cert's
         * SHA-256 fingerprint survives a service/process restart. Deliberately
         * NOT [AppPrefs] members: they have no typed accessor, so they never
         * appear in Settings. Both values are secret and must never be logged.
         */
        const val KEY_MANAGEMENT_TLS_P12 = "management_tls_p12"
        const val KEY_MANAGEMENT_TLS_PASSWORD = "management_tls_password"

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
