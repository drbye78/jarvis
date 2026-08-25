package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.config.ProviderSettings

/**
 * Plain (non-secret) app preferences: onboarding state, user-stop flag,
 * provider selection, wake-word sensitivity. Changing provider settings
 * requires a service restart to rebuild the graph (documented in Settings UI).
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    private val secure by lazy { SecurePrefs.get(context.applicationContext) }

    var onboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDED, value).apply()

    /** True after the user explicitly pressed Stop; blocks auto-restart. */
    var userStopped: Boolean
        get() = prefs.getBoolean(KEY_USER_STOPPED, false)
        set(value) = prefs.edit().putBoolean(KEY_USER_STOPPED, value).apply()

    var providerType: ProviderSettings.Type
        get() = if (prefs.getString(KEY_PROVIDER, null) == "openai") {
            ProviderSettings.Type.OPENAI_COMPAT
        } else {
            ProviderSettings.Type.GIGACHAT
        }
        set(value) = prefs.edit()
            .putString(KEY_PROVIDER, if (value == ProviderSettings.Type.OPENAI_COMPAT) "openai" else "gigachat")
            .apply()

    var openAiBaseUrl: String
        get() = prefs.getString(KEY_OPENAI_URL, ProviderSettings.DEFAULT.openAiBaseUrl)!!
        set(value) = prefs.edit().putString(KEY_OPENAI_URL, value).apply()

    var openAiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, ProviderSettings.DEFAULT.openAiModel)!!
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    var openAiApiKey: String
        get() = secure.getString(KEY_OPENAI_APIKEY, "") ?: ""
        set(value) = secure.edit().putString(KEY_OPENAI_APIKEY, value).apply()

    var wakeSensitivity: Float
        get() = prefs.getFloat(KEY_WAKE_SENSITIVITY, 0.6f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_SENSITIVITY, value).apply()

    fun loadProviderSettings(): ProviderSettings = ProviderSettings(
        type = providerType,
        openAiBaseUrl = openAiBaseUrl,
        openAiModel = openAiModel,
        wakeSensitivity = wakeSensitivity,
    )

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_USER_STOPPED = "user_stopped"
        const val KEY_PROVIDER = "provider_type"
        const val KEY_OPENAI_URL = "openai_base_url"
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_OPENAI_APIKEY = "openai_api_key"
        const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
    }
}
