package com.jarvis.assistant.util

import android.content.Context

/**
 * Per-user credential store backed by a [SecretVault].
 *
 * This used to be an `object` with a `lateinit var ctx` filled by
 * a separate `init(context)` call — any property access before that call
 * crashed with a bare `UninitializedPropertyAccessException`. It is a proper
 * class with an Application-owned singleton:
 *
 * - [init] runs once in `JarvisApplication.onCreate` (always before any
 *   activity/service/receiver in the process) and returns the singleton;
 * - [get] is the access point for app-process code and fails FAST with a
 *   descriptive contract message if [init] somehow did not run;
 * - [peek] is the null-safe read for paths that may execute outside the app
 *   lifecycle (JVM tests with injected fakes, wake-word engine failure
 *   reasons) — they degrade instead of crashing.
 *
 * The backing store is now [KeystoreVault] (AndroidKeyStore AES-GCM).
 * The deprecated EncryptedSharedPreferences dependency is gone.
 */
class CredentialsStore(private val vault: SecretVault) {

    var picovoiceKey: String
        get() = vault.getString(SecretVault.KEY_PICOVOICE) ?: ""
        set(v) { vault.putString(SecretVault.KEY_PICOVOICE, v.trim()) }
    var saluteClientId: String
        get() = vault.getString(SecretVault.KEY_SALUTE_ID) ?: ""
        set(v) { vault.putString(SecretVault.KEY_SALUTE_ID, v.trim()) }
    var saluteClientSecret: String
        get() = vault.getString(SecretVault.KEY_SALUTE_SECRET) ?: ""
        set(v) { vault.putString(SecretVault.KEY_SALUTE_SECRET, v.trim()) }
    var gigaChatClientId: String
        get() = vault.getString(SecretVault.KEY_GIGA_ID) ?: ""
        set(v) { vault.putString(SecretVault.KEY_GIGA_ID, v.trim()) }
    var gigaChatClientSecret: String
        get() = vault.getString(SecretVault.KEY_GIGA_SECRET) ?: ""
        set(v) { vault.putString(SecretVault.KEY_GIGA_SECRET, v.trim()) }

    /**
     * Yandex Cloud API key for SpeechKit v3 (ASR + TTS when the Yandex
     * backend is selected). Not part of [hasMandatoryApiKeys]: only one speech
     * backend is active at a time, so demanding both credential sets would
     * block a user who legitimately uses only Yandex.
     */
    var yandexApiKey: String
        get() = vault.getString(SecretVault.KEY_YANDEX_API_KEY) ?: ""
        set(v) { vault.putString(SecretVault.KEY_YANDEX_API_KEY, v.trim()) }

    /**
     * Yandex MapKit Mobile SDK key. A DIFFERENT credential from
     * [yandexApiKey]: MapKit needs a Maps API key, not a Cloud
     * service-account key, so neither can be substituted for the other.
     */
    var mapKitApiKey: String
        get() = vault.getString(SecretVault.KEY_MAPKIT_API_KEY) ?: ""
        set(v) { vault.putString(SecretVault.KEY_MAPKIT_API_KEY, v.trim()) }

    /**
     * Optional proxy for the Open-Meteo API only, e.g. `host:port`,
     * `http://user:pass@host:port` or `socks5://host:port`. Vault-backed
     * because the URL may embed credentials. Blank = direct connection.
     * Read LIVE per weather request and never applied to the shared client.
     */
    var openMeteoProxy: String
        get() = vault.getString(SecretVault.KEY_OPEN_METEO_PROXY) ?: ""
        set(v) { vault.putString(SecretVault.KEY_OPEN_METEO_PROXY, v.trim()) }

    /**
     * Auth secret for one configured MCP server, keyed by its stable
     * [com.jarvis.assistant.mcp.McpServerConfig.id]. Blank when no secret is
     * stored. Deliberately an ARGUMENT-taking accessor, not a zero-arg
     * property: the settings anti-drop reflection enumerates zero-arg getters,
     * and a per-server map has no single property it could name.
     */
    fun mcpSecret(serverId: String): String =
        vault.getString(SecretVault.mcpSecretKey(serverId)) ?: ""

    /** Store (or, with a blank [value], clear) the secret for [serverId]. */
    fun setMcpSecret(serverId: String, value: String) {
        vault.putString(SecretVault.mcpSecretKey(serverId), value.trim())
    }

    /**
     * Secret for one configured smart-home integration, keyed by its stable
     * config id plus a [field] name (e.g. `"token"` for Home Assistant). Blank
     * when none is stored. Argument-keyed for the same reason as [mcpSecret]:
     * there is no single zero-arg property a per-integration map could name.
     */
    fun homeSecret(providerId: String, field: String): String =
        vault.getString(SecretVault.homeSecretKey(providerId, field)) ?: ""

    /** Store (or, with a blank [value], clear) the [field] secret for [providerId]. */
    fun setHomeSecret(providerId: String, field: String, value: String) {
        vault.putString(SecretVault.homeSecretKey(providerId, field), value.trim())
    }

    /**
     * The MANDATORY keys: SaluteSpeech (ASR+TTS) and GigaChat (LLM).
     *
     * The old `hasRequiredSber()` also demanded the Picovoice key,
     * which is OPTIONAL — the default Sherpa-ONNX engine runs fully offline
     * without it. The predicate lied about what "required" means; the
     * Picovoice key is checked separately (only when the Porcupine engine is
     * selected) via [hasPicovoiceKey].
     */
    fun hasMandatoryApiKeys(): Boolean =
        saluteClientId.isNotBlank() && saluteClientSecret.isNotBlank() &&
            gigaChatClientId.isNotBlank() && gigaChatClientSecret.isNotBlank()

    /** Engine-optional key: only the Porcupine engine needs it. */
    fun hasPicovoiceKey(): Boolean = picovoiceKey.isNotBlank()

    /** Wipes every stored credential (Settings "clear" path). */
    fun clearAll() {
        vault.clear()
    }

    companion object {
        @Volatile
        private var instance: CredentialsStore? = null

        /** Construct (once) and return the process singleton. */
        fun init(context: Context): CredentialsStore =
            instance ?: synchronized(this) {
                instance ?: CredentialsStore(
                    KeystoreVault.get(context.applicationContext),
                ).also { instance = it }
            }

        /**
         * Test/JVM seam: construct the singleton with an injected vault
         * (production uses [KeystoreVault]; tests pass an in-memory fake).
         */
        fun initForTests(vault: SecretVault): CredentialsStore =
            instance ?: synchronized(this) {
                instance ?: CredentialsStore(vault).also { instance = it }
            }

        /** The singleton; fails fast with the contract message if [init] never ran. */
        fun get(): CredentialsStore = requireNotNull(instance) {
            "CredentialsStore accessed before init() — " +
                "JarvisApplication.onCreate must run first (app-process misuse)"
        }

        /** Null-safe read for non-lifecycle paths (tests, failure reasons). */
        fun peek(): CredentialsStore? = instance
    }
}
