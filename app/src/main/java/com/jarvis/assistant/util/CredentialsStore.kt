package com.jarvis.assistant.util
import android.content.Context
import android.content.SharedPreferences

/**
 * Per-user credential store backed by [SecurePrefs].
 *
 * Audit #30: this used to be an `object` with a `lateinit var ctx` filled by
 * a separate `init(context)` call — any property access before that call
 * crashed with a bare `UninitializedPropertyAccessException`. It is now a
 * proper class with an Application-owned singleton:
 *
 * - [init] runs once in `JarvisApplication.onCreate` (always before any
 *   activity/service/receiver in the process) and returns the singleton;
 * - [get] is the access point for app-process code and fails FAST with a
 *   descriptive contract message if [init] somehow did not run;
 * - [peek] is the null-safe read for paths that may execute outside the app
 *   lifecycle (JVM tests with injected fakes, wake-word engine failure
 *   reasons) — they degrade instead of crashing.
 */
class CredentialsStore(private val prefs: SharedPreferences) {

    var picovoiceKey: String
        get() = prefs.getString(KEY_PICOVOICE, "") ?: ""
        set(v) { prefs.edit().putString(KEY_PICOVOICE, v.trim()).commit() }
    var saluteClientId: String
        get() = prefs.getString(KEY_SALUTE_ID, "") ?: ""
        set(v) { prefs.edit().putString(KEY_SALUTE_ID, v.trim()).commit() }
    var saluteClientSecret: String
        get() = prefs.getString(KEY_SALUTE_SECRET, "") ?: ""
        set(v) { prefs.edit().putString(KEY_SALUTE_SECRET, v.trim()).commit() }
    var gigaChatClientId: String
        get() = prefs.getString(KEY_GIGA_ID, "") ?: ""
        set(v) { prefs.edit().putString(KEY_GIGA_ID, v.trim()).commit() }
    var gigaChatClientSecret: String
        get() = prefs.getString(KEY_GIGA_SECRET, "") ?: ""
        set(v) { prefs.edit().putString(KEY_GIGA_SECRET, v.trim()).commit() }

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

    companion object {
        @Volatile
        private var instance: CredentialsStore? = null

        /** Construct (once) and return the process singleton. */
        fun init(context: Context): CredentialsStore =
            instance ?: synchronized(this) {
                instance ?: CredentialsStore(
                    SecurePrefs.get(context.applicationContext),
                ).also { instance = it }
            }

        /** The singleton; fails fast with the contract message if [init] never ran. */
        fun get(): CredentialsStore = requireNotNull(instance) {
            "CredentialsStore accessed before init() — " +
                "JarvisApplication.onCreate must run first (app-process misuse)"
        }

        /** Null-safe read for non-lifecycle paths (tests, failure reasons). */
        fun peek(): CredentialsStore? = instance

        private const val KEY_PICOVOICE = "picovoice_key"
        private const val KEY_SALUTE_ID = "salute_client_id"
        private const val KEY_SALUTE_SECRET = "salute_client_secret"
        private const val KEY_GIGA_ID = "gigachat_client_id"
        private const val KEY_GIGA_SECRET = "gigachat_client_secret"
    }
}
