package com.jarvis.assistant.util
import android.content.Context

object CredentialsStore {
    private lateinit var ctx: Context
    fun init(context: Context) { ctx = context.applicationContext }
    /** True once [init] has run (always the case in the live app). */
    val isInitialized: Boolean get() = ::ctx.isInitialized
    private val prefs get() = SecurePrefs.get(ctx)

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

    fun hasRequiredSber(): Boolean =
        saluteClientId.isNotBlank() && saluteClientSecret.isNotBlank() &&
        gigaChatClientId.isNotBlank() && gigaChatClientSecret.isNotBlank() &&
        picovoiceKey.isNotBlank()

    private const val KEY_PICOVOICE = "picovoice_key"
    private const val KEY_SALUTE_ID = "salute_client_id"
    private const val KEY_SALUTE_SECRET = "salute_client_secret"
    private const val KEY_GIGA_ID = "gigachat_client_id"
    private const val KEY_GIGA_SECRET = "gigachat_client_secret"
}
