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
class AppPrefs(
    /**
     * Nullable ONLY for the 0.7 test seam: when [prefsOverride] is supplied
     * no Android framework type is touched. Production callers pass a real
     * context and the requireNotNull guard is invisible.
     */
    context: Context?,
    vaultOverride: SecretVault? = null,
    /** 0.7 test seam: JVM tests inject an in-memory [SharedPreferences]. */
    prefsOverride: SharedPreferences? = null,
) {

    private val prefs: SharedPreferences =
        prefsOverride
            ?: requireNotNull(context) { "AppPrefs needs a context when no prefsOverride is supplied" }
                .applicationContext.getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)

    /** 0.7: raw handle for reactive wrappers ([PrefsFlow]) — same-file singleton. */
    internal fun rawPrefs(): SharedPreferences = prefs

    private val vault: SecretVault by lazy {
        vaultOverride
            ?: KeystoreVault.get(
                // vault is lazy: only touched on the first secret access, which
                // in production always happens with a real context attached.
                requireNotNull(context) { "AppPrefs needs a context for the secret vault" }
                    .applicationContext,
            )
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

    // ------------------------------------------------------------------
    // COGNITIVE_PLAN Phase 1 (§9.2/§12.4): the memory switches. ALL of them
    // are user-configurable by owner decision (§12.4: "a plan default is the
    // initial value of a user-visible switch, never a hard-coded behaviour")
    // and ALL are consumed reactively via [PrefsFlow] — a Settings toggle
    // applies from the next turn, no restart (plan principle 5).
    // ------------------------------------------------------------------

    /**
     * Master kill switch (plan principle 6): false → byte-identical prompts
     * to the pre-cognitive composer, empty queue processing, zero extra
     * cloud calls. Explicit memory tools report honestly that memory is off.
     */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    /**
     * Automatic fact extraction after turns (plan §6.2). Default OFF by
     * design: it flips on only after the Phase 1 evaluation gate measures
     * precision ≥ 0.85 / recall ≥ 0.7 on the fixture set. Explicit
     * remember_fact writes work regardless of this switch.
     */
    var memoryAutoExtract: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_AUTO_EXTRACT, false)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_AUTO_EXTRACT, value).apply()

    /**
     * Cloud extraction/summarization egress (plan §9.2). OFF stops all
     * queued cognitive cloud calls (they stay PENDING, never dropped or
     * faked); explicit tool writes stay local and keep working.
     */
    var memoryCloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_CLOUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_CLOUD_ENABLED, value).apply()

    /**
     * §12.4-2: sensitive-fact categories (HEALTH, politics, religion) are
     * visible-but-marked by default for this single-user device; this switch
     * controls whether they are INJECTED INTO PROMPTS at all (the inspector
     * always shows them, marked).
     */
    var memorySensitiveVisible: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_SENSITIVE_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_SENSITIVE_VISIBLE, value).apply()

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

    // ------------------------------------------------------------------
    // COGNITIVE_PLAN Phase 2 (§8/§12.4-1): the behaviour switches. The
    // proactive layer ships DEFAULT OFF (trust first — §12.4-1); quiet
    // hours and the daily quota are user-tunable. All are consumed
    // reactively via [PrefsFlow] — live-toggle regression tests included.
    // ------------------------------------------------------------------

    /** Proactive speech master switch (§8.3 gate 1). Default OFF. */
    var behaviorEnabled: Boolean
        get() = prefs.getBoolean(KEY_BEHAVIOR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BEHAVIOR_ENABLED, value).apply()

    /** Quiet-hours start (hour of day, inclusive). Default 23. */
    var behaviorQuietStart: Int
        get() = prefs.getInt(KEY_BEHAVIOR_QUIET_START, 23)
        set(value) = prefs.edit().putInt(KEY_BEHAVIOR_QUIET_START, value.coerceIn(0, 23)).apply()

    /** Quiet-hours end (hour of day, exclusive). Default 8. */
    var behaviorQuietEnd: Int
        get() = prefs.getInt(KEY_BEHAVIOR_QUIET_END, 8)
        set(value) = prefs.edit().putInt(KEY_BEHAVIOR_QUIET_END, value.coerceIn(0, 23)).apply()

    /** Global proactive utterances per day (§8.3 gate 6). Default 2. */
    var behaviorDailyQuota: Int
        get() = prefs.getInt(KEY_BEHAVIOR_DAILY_QUOTA, 2)
        set(value) = prefs.edit().putInt(KEY_BEHAVIOR_DAILY_QUOTA, value.coerceIn(1, 5)).apply()

    // ------------------------------------------------------------------
    // COGNITIVE_PLAN Phase 3 (§11/§12.4-3): the semantic-recall selector.
    // AUTO (the default) resolves through the benchmark winner — either the
    // on-device «Проверить качество поиска» run or the CI ship-or-reject
    // verdict — and every unavailable branch fails closed to OFF. Consumed
    // reactively via [PrefsFlow]; a live-toggle regression test asserts the
    // push (AGENTS.md convention).
    // ------------------------------------------------------------------

    /** AUTO | CLOUD | LOCAL | OFF ([EmbedderChoice]). Default AUTO. */
    var memoryEmbedder: String
        get() = prefs.getString(KEY_MEMORY_EMBEDDER, "AUTO") ?: "AUTO"
        set(value) = prefs.edit().putString(KEY_MEMORY_EMBEDDER, value).apply()

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

    /**
     * 0.7: keys are internal (not private) so the reactive [PrefsFlow]
     * wrapper can fan out changes by key. Treat them as storage layout.
     */
    internal companion object {
        internal const val KEY_ONBOARDED = "onboarded"
        internal const val KEY_USER_STOPPED = "user_stopped"
        internal const val KEY_PROVIDER = "provider_type"
        internal const val KEY_OPENAI_URL = "openai_base_url"
        internal const val KEY_OPENAI_MODEL = "openai_model"
        internal const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        internal const val KEY_WAKE_MODEL = "wake_word_model"
        internal const val KEY_CUSTOM_WAKE_PATH = "custom_wake_word_path"
        internal const val KEY_WAKE_ENGINE = "wake_word_engine"
        internal const val KEY_SHERPA_ONNX = "sherpa_onnx_path"
        internal const val KEY_SHERPA_KEYWORD = "sherpa_custom_keyword"
        internal const val KEY_VOICE_STOP = "voice_stop_enabled"
        internal const val KEY_MUSIC_PLAYER = "preferred_music_player"
        internal const val KEY_AEC_MODE = "aec_mode"
        internal const val KEY_FOLLOW_UP_ENABLED = "follow_up_enabled"
        internal const val KEY_FOLLOW_UP_WINDOW_MS = "follow_up_window_ms"
        internal const val KEY_TTS_VOICE = "tts_voice"
        internal const val KEY_MEMORY_ENABLED = "memory_enabled"
        internal const val KEY_MEMORY_AUTO_EXTRACT = "memory_auto_extract"
        internal const val KEY_MEMORY_CLOUD_ENABLED = "memory_cloud_enabled"
        internal const val KEY_MEMORY_SENSITIVE_VISIBLE = "memory_sensitive_visible"
        internal const val KEY_BEHAVIOR_ENABLED = "behavior_enabled"
        internal const val KEY_BEHAVIOR_QUIET_START = "behavior_quiet_start"
        internal const val KEY_BEHAVIOR_QUIET_END = "behavior_quiet_end"
        internal const val KEY_BEHAVIOR_DAILY_QUOTA = "behavior_daily_quota"
        internal const val KEY_MEMORY_EMBEDDER = "memory_embedder"
    }
}
