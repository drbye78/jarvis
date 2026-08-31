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

    /** Wake-word model: "builtin" | "custom_bundled" | "custom_user". */
    var wakeWordModel: String
        get() = prefs.getString(KEY_WAKE_MODEL, "custom_bundled") ?: "custom_bundled"
        set(value) = prefs.edit().putString(KEY_WAKE_MODEL, value).apply()

    /** Absolute path to a user-supplied .ppn (only when wakeWordModel="custom_user"). */
    var customWakeWordPath: String
        get() = prefs.getString(KEY_CUSTOM_WAKE_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_WAKE_PATH, value).apply()

    /**
     * Selected wake-word engine: "sherpa" | "porcupine".
     *
     * Default SHERPA: it is the zero-config engine (model bundled in assets),
     * while the previous "porcupine" default required a jarvis_ru.ppn asset
     * the repo does not ship — a fresh install was DEAF until the user found
     * the engine setting. Porcupine stays available for users who add a key
     * and their own .ppn.
     */
    var wakeWordEngine: String
        get() = prefs.getString(KEY_WAKE_ENGINE, "sherpa") ?: "sherpa"
        set(value) = prefs.edit().putString(KEY_WAKE_ENGINE, value).apply()

    /**
     * Absolute path to a user-supplied Sherpa-ONNX model directory. Blank means
     * "use the bundled model" (extracted from assets by the graph builder).
     */
    var sherpaOnnxPath: String
        get() = prefs.getString(KEY_SHERPA_ONNX, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SHERPA_ONNX, value).apply()

    /**
     * The user's preferred default music player: "auto" (Яндекс Музыка first,
     * the project default) or a package name — com.zvooq.openplay (Звук),
     * ru.yandex.music, com.vk.music. Set from the Settings «Музыка» card;
     * consumed by [com.jarvis.assistant.media.MusicAppCatalog] as resolution
     * step 3 (an explicit voice hint "включи в Звуке" still wins).
     */
    var preferredMusicPlayer: String
        get() = prefs.getString(KEY_MUSIC_PLAYER, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_MUSIC_PLAYER, value).apply()

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
        const val KEY_WAKE_MODEL = "wake_word_model"
        const val KEY_CUSTOM_WAKE_PATH = "custom_wake_word_path"
        const val KEY_WAKE_ENGINE = "wake_word_engine"
        const val KEY_SHERPA_ONNX = "sherpa_onnx_path"
        const val KEY_MUSIC_PLAYER = "preferred_music_player"
    }
}
