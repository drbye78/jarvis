package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.config.ProviderSettings

/**
 * Plain (non-secret) app preferences: onboarding state, user-stop flag,
 * provider selection, wake-word configuration. Changing provider settings
 * requires a service restart to rebuild the graph (documented in Settings UI).
 *
 * Secrets (the OpenAI-compatible API key) are NOT plain prefs: they route
 * through the [SecretVault] (Keystore-encrypted in production).
 */
class AppPrefs(context: Context, vaultOverride: SecretVault? = null) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    private val vault: SecretVault by lazy {
        vaultOverride ?: KeystoreVault.get(context.applicationContext)
    }

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
        get() = vault.getString(SecretVault.KEY_OPENAI_API_KEY) ?: ""
        set(value) = vault.putString(SecretVault.KEY_OPENAI_API_KEY, value.trim())

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

    /**
     * Echo-cancellation mode (Settings «Эхоподавление»): "off" | "hardware" |
     * "software". Default OFF — all AEC modes are opt-in; HARDWARE switches
     * capture to VOICE_COMMUNICATION + platform AEC (Phase A), SOFTWARE runs
     * the built-in canceller with electrical far-end references (Phase B).
     * Applies after service restart (the AudioRecord must be rebuilt).
     */
    var aecMode: String
        get() = prefs.getString(KEY_AEC_MODE, "off") ?: "off"
        set(value) = prefs.edit().putString(KEY_AEC_MODE, value).apply()

    /**
     * Follow-up window mode (Settings «Продолжение диалога»): opt-in; after a
     * spoken reply the mic window opens for [followUpWindowMs] and speech
     * onset starts the next turn WITHOUT the wake word. Live-updatable (no
     * restart) via the service binder.
     */
    var followUpEnabled: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_UP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_FOLLOW_UP_ENABLED, value).apply()

    /** Follow-up window length in ms (clamped 2..12 s by the controller). */
    var followUpWindowMs: Long
        get() = prefs.getLong(KEY_FOLLOW_UP_WINDOW_MS, 5_000L)
        set(value) = prefs.edit().putLong(KEY_FOLLOW_UP_WINDOW_MS, value).apply()

    /**
     * Y6: TTS voice for the assistant's speech (Settings «Голос» card).
     * "Mila" is the verified default; a free-text Salute voice ID is stored
     * as-is for advanced users. Read PER SENTENCE by the session lane
     * (TurnRunner voiceSource), so a change applies to the next spoken
     * sentence — no service restart.
     */
    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "Mila") ?: "Mila"
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    fun loadProviderSettings(): ProviderSettings = ProviderSettings(
        type = providerType,
        openAiBaseUrl = openAiBaseUrl,
        openAiModel = openAiModel,
    )

    /**
     * Custom Sherpa wake-word text (FIXPLAN C). Blank = the bundled
     * "Jarvis" keyword from assets. A non-blank value is an ENGLISH word or
     * short phrase (the bundled gigaspeech KWS model is English-BPE — the
     * tokenizer rejects anything it cannot encode, and Settings validates
     * with the same tokenizer before saving).
     */
    var sherpaCustomKeyword: String
        get() = prefs.getString(KEY_SHERPA_KEYWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SHERPA_KEYWORD, value.trim()).apply()

    /**
     * Voice stop toggle (FIXPLAN B). Default mirrors
     * [com.jarvis.assistant.config.JarvisConfig.voiceStopEnabled]. Read by
     * the session state collector every state change — applies live.
     */
    var voiceStopEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_STOP, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_STOP, value).apply()

    private companion object {
        const val KEY_ONBOARDED = "onboarded"
        const val KEY_USER_STOPPED = "user_stopped"
        const val KEY_PROVIDER = "provider_type"
        const val KEY_OPENAI_URL = "openai_base_url"
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        const val KEY_WAKE_MODEL = "wake_word_model"
        const val KEY_CUSTOM_WAKE_PATH = "custom_wake_word_path"
        const val KEY_WAKE_ENGINE = "wake_word_engine"
        const val KEY_SHERPA_ONNX = "sherpa_onnx_path"
        const val KEY_SHERPA_KEYWORD = "sherpa_custom_keyword"
        const val KEY_VOICE_STOP = "voice_stop_enabled"
        const val KEY_MUSIC_PLAYER = "preferred_music_player"
        const val KEY_AEC_MODE = "aec_mode"
        const val KEY_FOLLOW_UP_ENABLED = "follow_up_enabled"
        const val KEY_FOLLOW_UP_WINDOW_MS = "follow_up_window_ms"
        const val KEY_TTS_VOICE = "tts_voice"
    }
}
