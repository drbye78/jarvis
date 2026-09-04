package com.jarvis.assistant.util

import android.content.Context

/**
 * Per-user credential store backed by a [SecretVault].
 *
 * Audit #30: this used to be an `object` with a `lateinit var ctx` filled by
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
 * A3: the backing store is now [KeystoreVault] (AndroidKeyStore AES-GCM).
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
     * The MANDATORY keys: SaluteSpeech (ASR+TTS) and GigaChat (LLM).
     *
     * Audit #16: the old `hasRequiredSber()` also demanded the Picovoice key,
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
