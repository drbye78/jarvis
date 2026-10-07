package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.tts.YandexVoiceSpec

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

    /**
     * Persisted with the same wire spelling the settings card uses
     * ("gigachat" | "openai" | "yandex") so the write and the read below can
     * never drift. Keep in sync with `SettingsMapping.providerTypeFor`.
     */
    var providerType: ProviderSettings.Type
        get() = when (prefs.getString(KEY_PROVIDER, null)) {
            "openai" -> ProviderSettings.Type.OPENAI_COMPAT
            "yandex" -> ProviderSettings.Type.YANDEX
            else -> ProviderSettings.Type.GIGACHAT
        }
        set(value) {
            val persisted = when (value) {
                ProviderSettings.Type.OPENAI_COMPAT -> "openai"
                ProviderSettings.Type.YANDEX -> "yandex"
                ProviderSettings.Type.GIGACHAT -> "gigachat"
            }
            prefs.edit().putString(KEY_PROVIDER, persisted).apply()
        }

    var openAiBaseUrl: String
        get() = prefs.getString(KEY_OPENAI_URL, ProviderSettings.DEFAULT.openAiBaseUrl)!!
        set(value) = prefs.edit().putString(KEY_OPENAI_URL, value).apply()

    var openAiModel: String
        get() = prefs.getString(KEY_OPENAI_MODEL, ProviderSettings.DEFAULT.openAiModel)!!
        set(value) = prefs.edit().putString(KEY_OPENAI_MODEL, value).apply()

    /**
     * GigaChat-3 flavor for the native (`api.giga.chat/v2`) client — one of
     * [ProviderSettings.GIGACHAT_MODELS]. An unrecognized stored value falls
     * back to the default rather than sending a bogus model id (which the API
     * rejects with HTTP 400).
     */
    var gigaChatModel: String
        get() {
            val stored = prefs.getString(KEY_GIGACHAT_MODEL, null)
            return if (stored != null && stored in ProviderSettings.GIGACHAT_MODELS) {
                stored
            } else {
                ProviderSettings.DEFAULT_GIGACHAT_MODEL
            }
        }
        set(value) = prefs.edit().putString(KEY_GIGACHAT_MODEL, value).apply()

    /**
     * Yandex AI Studio model — one of [ProviderSettings.YANDEX_MODELS]. An
     * unrecognized stored value falls back to the default rather than sending
     * a bogus model id (which the API rejects with HTTP 400).
     */
    var yandexModel: String
        get() {
            val stored = prefs.getString(KEY_YANDEX_MODEL, null)
            return if (stored != null && stored in ProviderSettings.YANDEX_MODELS) {
                stored
            } else {
                ProviderSettings.DEFAULT_YANDEX_MODEL
            }
        }
        set(value) = prefs.edit().putString(KEY_YANDEX_MODEL, value).apply()

    /**
     * Yandex AI Studio folder id. Blank is allowed and means "let the API
     * key's service-account folder be implied" (no `x-folder-id` is sent).
     */
    var yandexFolderId: String
        get() = prefs.getString(KEY_YANDEX_FOLDER_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YANDEX_FOLDER_ID, value.trim()).apply()

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
     * The user's preferred default music player: "auto" (Яндекс Музыка first,
     * the project default) or a package name — com.zvooq.openplay (Звук),
     * ru.yandex.music, com.uma.musicvk. Set from the Settings «Музыка» card;
     * consumed by [com.jarvis.assistant.media.MusicAppCatalog] as resolution
     * step 3 (an explicit voice hint "включи в Звуке" still wins).
     */
    var preferredMusicPlayer: String
        get() = prefs.getString(KEY_MUSIC_PLAYER, "auto") ?: "auto"
        set(value) = prefs.edit().putString(KEY_MUSIC_PLAYER, value).apply()

    /**
     * Default place for weather questions (Settings «Погода»). Blank — the
     * default — means "auto-detect": fall back to the device location. A
     * configured value ALWAYS wins over GPS (owner decision), which is the
     * realistic primary path on the GMS-free target tablet.
     *
     * Read live per weather turn (never snapshotted at graph build), so a
     * change applies without a service restart.
     */
    var weatherLocation: String
        get() = prefs.getString(KEY_WEATHER_LOCATION, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WEATHER_LOCATION, value.trim()).apply()

    /**
     * Active weather data provider (Settings «Погода»): "open_meteo" |
     * "project_eol". Stored as a plain string so [AppPrefs] stays free of the
     * weather lane's types; [com.jarvis.assistant.weather.WeatherProvider.fromId]
     * parses it tolerantly (unknown → the default).
     *
     * Read live on every weather turn, so a change applies without a restart.
     * The stored default is [DEFAULT_WEATHER_PROVIDER], which MUST agree with
     * [com.jarvis.assistant.weather.WeatherProvider.DEFAULT] — a test asserts
     * they agree. Project EOL is the default because
     * `api.open-meteo.com` is DPI-blocked from Russian networks; an explicitly
     * stored "open_meteo" is preserved as-is (no migration).
     */
    var weatherProvider: String
        get() = prefs.getString(KEY_WEATHER_PROVIDER, DEFAULT_WEATHER_PROVIDER) ?: DEFAULT_WEATHER_PROVIDER
        set(value) = prefs.edit().putString(KEY_WEATHER_PROVIDER, value.trim()).apply()

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
    // The memory switches. ALL of them
    // are user-configurable by owner decision ("a default is the
    // initial value of a user-visible switch, never a hard-coded behaviour")
    // and ALL are consumed reactively via [PrefsFlow] — a Settings toggle
    // applies from the next turn, no restart.
    // ------------------------------------------------------------------

    /**
     * Master kill switch: false → byte-identical prompts
     * to the pre-cognitive composer, empty queue processing, zero extra
     * cloud calls. Explicit memory tools report honestly that memory is off.
     */
    var memoryEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_ENABLED, value).apply()

    /**
     * Automatic fact extraction after turns. Default OFF by
     * design: it flips on only after the first evaluation gate measures
     * precision ≥ 0.85 / recall ≥ 0.7 on the fixture set. Explicit
     * remember_fact writes work regardless of this switch.
     */
    var memoryAutoExtract: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_AUTO_EXTRACT, false)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_AUTO_EXTRACT, value).apply()

    /**
     * Cloud extraction/summarization egress. OFF stops all
     * queued cognitive cloud calls (they stay PENDING, never dropped or
     * faked); explicit tool writes stay local and keep working.
     */
    var memoryCloudEnabled: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_CLOUD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_CLOUD_ENABLED, value).apply()

    /**
     * Sensitive-fact categories (HEALTH, politics, religion) are
     * visible-but-marked by default for this single-user device; this switch
     * controls whether they are INJECTED INTO PROMPTS at all (the inspector
     * always shows them, marked).
     */
    var memorySensitiveVisible: Boolean
        get() = prefs.getBoolean(KEY_MEMORY_SENSITIVE_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_MEMORY_SENSITIVE_VISIBLE, value).apply()

    /**
     * SBER TTS voice for the assistant's speech (Settings «Голос» card),
     * persisted under `tts_voice`. "Mila" is the verified default; a free-text
     * Salute voice ID is stored as-is for advanced users. Read PER SENTENCE by
     * the session lane (TurnRunner voiceSource), so a change applies to the next
     * spoken sentence — no service restart.
     *
     * This pref is SBER-ONLY: it is never handed to the Yandex backend. The
     * Yandex speaker lives in [yandexTtsVoice] and is validated against
     * [com.jarvis.assistant.speech.tts.VoiceCatalog.YANDEX_VOICES].
     */
    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, "Mila") ?: "Mila"
        set(value) = prefs.edit().putString(KEY_TTS_VOICE, value).apply()

    // ------------------------------------------------------------------
    // Speech backend (Sber SaluteSpeech vs Yandex SpeechKit v3). ONE choice
    // drives recognition and synthesis together. The
    // backend is consumed at graph construction — each provider needs its own
    // channel and auth scheme — so a change applies after the next service
    // restart, exactly like the LLM provider selector.
    // ------------------------------------------------------------------

    /** Active speech backend. Unknown/absent values degrade to [SpeechBackend.DEFAULT]. */
    var speechBackend: SpeechBackend
        get() = SpeechBackend.fromPref(prefs.getString(KEY_SPEECH_BACKEND, null))
        set(value) = prefs.edit().putString(KEY_SPEECH_BACKEND, SpeechBackend.toPref(value)).apply()

    /**
     * Yandex Cloud API key. Routed to the Keystore vault (same slot rule as
     * [openAiApiKey]) — never to the plain SharedPreferences file.
     */
    var yandexApiKey: String
        get() = vault.getString(SecretVault.KEY_YANDEX_API_KEY) ?: ""
        set(value) = vault.putString(SecretVault.KEY_YANDEX_API_KEY, value.trim())

    /**
     * Yandex MapKit Mobile SDK key, routed to the Keystore vault like every
     * other secret. NOT the same credential as [yandexApiKey] (SpeechKit / AI
     * Studio): MapKit needs its own Maps API key, so it gets its own slot.
     */
    var mapKitApiKey: String
        get() = vault.getString(SecretVault.KEY_MAPKIT_API_KEY) ?: ""
        set(value) = vault.putString(SecretVault.KEY_MAPKIT_API_KEY, value.trim())

    /**
     * Yandex TTS voice id (e.g. `marina`), persisted under `yandex_tts_voice`.
     * Read PER SENTENCE by the session lane's voice source, so a change applies
     * to the next spoken sentence without a restart. Blank falls back to the
     * config default.
     *
     * VALIDATED: this is Yandex-ONLY and must be a member of
     * [com.jarvis.assistant.speech.tts.VoiceCatalog.YANDEX_VOICES] (matched
     * case-insensitively and canonicalized by
     * [com.jarvis.assistant.speech.tts.VoiceResolver]); an unknown or foreign id
     * falls back to
     * [com.jarvis.assistant.speech.tts.YandexVoiceSpec.DEFAULT_VOICE] rather
     * than being sent as a hard service error.
     */
    var yandexTtsVoice: String
        get() = prefs.getString(KEY_YANDEX_TTS_VOICE, null)
            ?: com.jarvis.assistant.config.JarvisConfig().yandexTtsVoice
        set(value) = prefs.edit().putString(KEY_YANDEX_TTS_VOICE, value.trim()).apply()

    /**
     * Yandex TTS role for the selected voice (e.g. `good`, `strict`). Blank
     * means "send no role hint at all", which is what most voices want.
     */
    var yandexTtsRole: String
        get() = prefs.getString(KEY_YANDEX_TTS_ROLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_YANDEX_TTS_ROLE, value.trim()).apply()

    /**
     * Yandex TTS speaking rate, `0.1`–`3.0` (`1.0` = normal). Read per sentence
     * alongside [yandexTtsVoice]/[yandexTtsRole], so it is LIVE. Clamped on BOTH
     * read and write; a non-finite or out-of-range stored value degrades to the
     * service default rather than reaching the request.
     */
    var yandexTtsSpeed: Float
        get() = clampYandexTtsSpeed(
            prefs.getFloat(KEY_YANDEX_TTS_SPEED, YandexVoiceSpec.DEFAULT_SPEED),
        )
        set(value) = prefs.edit()
            .putFloat(KEY_YANDEX_TTS_SPEED, clampYandexTtsSpeed(value))
            .apply()

    // ------------------------------------------------------------------
    // The behaviour switches. The
    // proactive layer ships DEFAULT OFF (trust first); quiet
    // hours and the daily quota are user-tunable. All are consumed
    // reactively via [PrefsFlow] — live-toggle regression tests included.
    // ------------------------------------------------------------------

    /** Proactive speech master switch. Default OFF. */
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

    /** Global proactive utterances per day. Default 2. */
    var behaviorDailyQuota: Int
        get() = prefs.getInt(KEY_BEHAVIOR_DAILY_QUOTA, 2)
        set(value) = prefs.edit().putInt(KEY_BEHAVIOR_DAILY_QUOTA, value.coerceIn(1, 5)).apply()

    // ------------------------------------------------------------------
    // The semantic-recall selector.
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

    /**
     * Configured MCP ("внешние инструменты") servers, held as ONE opaque JSON
     * blob from [com.jarvis.assistant.mcp.McpServerConfigCodec.encode]. Stored
     * as a plain string so [AppPrefs] stays free of the MCP lane's types; the
     * catalog decodes it on every use, so a change applies LIVE — no service
     * restart.
     *
     * Only the auth header NAME is part of the blob. The secret value lives in
     * the vault keyed by server id
     * ([com.jarvis.assistant.util.CredentialsStore.mcpSecret]).
     */
    var mcpServers: String
        get() = prefs.getString(KEY_MCP_SERVERS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MCP_SERVERS, value).apply()

    // ------------------------------------------------------------------
    // Smart-home integrations (HA first; Yandex/Tuya later). The provider
    // list, the curated-awareness entity set, the alias map and the grant list
    // are held as opaque JSON blobs decoded by the `home/` codecs, so AppPrefs
    // stays free of the home lane's types. The provider list is sealed at
    // service start (the HA backend is process-scoped); the alias/grant/entity
    // blobs and the awareness flag are read LIVE.
    // ------------------------------------------------------------------

    /** Configured smart-home integrations ([com.jarvis.assistant.home.HomeProviderConfig] JSON). */
    var homeProviders: String
        get() = prefs.getString(KEY_HOME_PROVIDERS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_HOME_PROVIDERS, value).apply()

    /** Curated awareness entity set — wire handles subscribed for notices (JSON array). */
    var homeEntities: String
        get() = prefs.getString(KEY_HOME_ENTITIES, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_HOME_ENTITIES, value).apply()

    /** Spoken-alias → [com.jarvis.assistant.home.HomeDeviceKey] map (JSON object). */
    var homeAliases: String
        get() = prefs.getString(KEY_HOME_ALIASES, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_HOME_ALIASES, value).apply()

    /** Persisted [com.jarvis.assistant.home.HomeGrant] list (JSON array). */
    var homeGrants: String
        get() = prefs.getString(KEY_HOME_GRANTS, "[]") ?: "[]"
        set(value) = prefs.edit().putString(KEY_HOME_GRANTS, value).apply()

    /** Proactive smart-home notice lane master switch. Default OFF. */
    var homeAwarenessEnabled: Boolean
        get() = prefs.getBoolean(KEY_HOME_AWARENESS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_HOME_AWARENESS_ENABLED, value).apply()

    // ------------------------------------------------------------------
    // R13 §14 optional external management surface. The MODE and PORT are
    // sealed at service start (the listener binds a specific interface:port),
    // so a change applies on the next service restart; the idle timeout is
    // read per request by the running server, so it is LIVE.
    // ------------------------------------------------------------------

    /**
     * Persisted management intent: "disabled" (default) | "localhost" | "lan".
     * This is the user's INTENT only — the in-memory `managementActive` flag
     * decides whether the socket is actually open, and is never persisted (a
     * reboot always drops LAN; LOCALHOST auto-activates safely).
     *
     * Stored as a plain string so [AppPrefs] stays free of the management
     * lane's types. Unknown/absent values degrade to "disabled".
     */
    var managementMode: String
        get() = prefs.getString(KEY_MANAGEMENT_MODE, DEFAULT_MANAGEMENT_MODE) ?: DEFAULT_MANAGEMENT_MODE
        set(value) = prefs.edit().putString(KEY_MANAGEMENT_MODE, value).apply()

    /** HTTPS listen port for the management server. Default [DEFAULT_MANAGEMENT_PORT]. */
    var managementPort: Int
        get() = prefs.getInt(KEY_MANAGEMENT_PORT, DEFAULT_MANAGEMENT_PORT)
        set(value) = prefs.edit().putInt(KEY_MANAGEMENT_PORT, value).apply()

    /**
     * LAN idle auto-close window: after this long WITHOUT an authenticated
     * request the listener closes and `active=false` (the mode pref still
     * reads "lan", shown as stopped). Default [DEFAULT_MANAGEMENT_IDLE_TIMEOUT_MS]
     * (15 min). Read LIVE by the running server.
     */
    var managementIdleTimeoutMs: Long
        get() = prefs.getLong(KEY_MANAGEMENT_IDLE_TIMEOUT_MS, DEFAULT_MANAGEMENT_IDLE_TIMEOUT_MS)
        set(value) = prefs.edit().putLong(KEY_MANAGEMENT_IDLE_TIMEOUT_MS, value).apply()

    fun loadProviderSettings(): ProviderSettings = ProviderSettings(
        type = providerType,
        openAiBaseUrl = openAiBaseUrl,
        openAiModel = openAiModel,
        gigaChatModel = gigaChatModel,
        yandexModel = yandexModel,
        yandexFolderId = yandexFolderId,
    )

    /**
     * Custom Sherpa wake-word text. Blank = the bundled
     * "Jarvis" keyword from assets. A non-blank value is an ENGLISH word or
     * short phrase (the bundled gigaspeech KWS model is English-BPE — the
     * tokenizer rejects anything it cannot encode, and Settings validates
     * with the same tokenizer before saving).
     */
    var sherpaCustomKeyword: String
        get() = prefs.getString(KEY_SHERPA_KEYWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SHERPA_KEYWORD, value.trim()).apply()

    /**
     * Voice stop toggle. Default mirrors
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
        internal const val KEY_GIGACHAT_MODEL = "gigachat_model"
        internal const val KEY_YANDEX_MODEL = "yandex_model"
        internal const val KEY_YANDEX_FOLDER_ID = "yandex_folder_id"
        internal const val KEY_WAKE_SENSITIVITY = "wake_sensitivity"
        internal const val KEY_WAKE_MODEL = "wake_word_model"
        internal const val KEY_CUSTOM_WAKE_PATH = "custom_wake_word_path"
        internal const val KEY_WAKE_ENGINE = "wake_word_engine"
        internal const val KEY_SHERPA_KEYWORD = "sherpa_custom_keyword"
        internal const val KEY_VOICE_STOP = "voice_stop_enabled"
        internal const val KEY_MUSIC_PLAYER = "preferred_music_player"
        internal const val KEY_AEC_MODE = "aec_mode"
        internal const val KEY_WEATHER_LOCATION = "weather_location"

        /** Active weather provider id; see [weatherProvider]. */
        internal const val KEY_WEATHER_PROVIDER = "weather_provider"

        /**
         * The provider a fresh install uses; MUST match
         * [com.jarvis.assistant.weather.WeatherProvider.DEFAULT], pinned by test.
         */
        internal const val DEFAULT_WEATHER_PROVIDER = "project_eol"
        internal const val KEY_FOLLOW_UP_ENABLED = "follow_up_enabled"
        internal const val KEY_FOLLOW_UP_WINDOW_MS = "follow_up_window_ms"
        internal const val KEY_TTS_VOICE = "tts_voice"
        internal const val KEY_SPEECH_BACKEND = "speech_backend"
        internal const val KEY_YANDEX_TTS_VOICE = "yandex_tts_voice"
        internal const val KEY_YANDEX_TTS_ROLE = "yandex_tts_role"
        internal const val KEY_YANDEX_TTS_SPEED = "yandex_tts_speed"
        internal const val KEY_MEMORY_ENABLED = "memory_enabled"
        internal const val KEY_MEMORY_AUTO_EXTRACT = "memory_auto_extract"
        internal const val KEY_MEMORY_CLOUD_ENABLED = "memory_cloud_enabled"
        internal const val KEY_MEMORY_SENSITIVE_VISIBLE = "memory_sensitive_visible"
        internal const val KEY_BEHAVIOR_ENABLED = "behavior_enabled"
        internal const val KEY_BEHAVIOR_QUIET_START = "behavior_quiet_start"
        internal const val KEY_BEHAVIOR_QUIET_END = "behavior_quiet_end"
        internal const val KEY_BEHAVIOR_DAILY_QUOTA = "behavior_daily_quota"
        internal const val KEY_MEMORY_EMBEDDER = "memory_embedder"

        /** MCP server list blob; see [mcpServers]. LIVE (no restart). */
        internal const val KEY_MCP_SERVERS = "mcp_servers"

        /** Smart-home provider list blob; see [homeProviders]. SERVICE_RESTART. */
        internal const val KEY_HOME_PROVIDERS = "home_providers"

        /** Discovered-device cache blob; see [homeEntities]. LIVE. */
        internal const val KEY_HOME_ENTITIES = "home_entities"

        /** Alias map blob; see [homeAliases]. LIVE. */
        internal const val KEY_HOME_ALIASES = "home_aliases"

        /** Grant list blob; see [homeGrants]. LIVE. */
        internal const val KEY_HOME_GRANTS = "home_grants"

        /** Proactive smart-home notice switch; see [homeAwarenessEnabled]. LIVE. */
        internal const val KEY_HOME_AWARENESS_ENABLED = "home_awareness_enabled"

        /** R13 management intent; see [managementMode]. SERVICE_RESTART. */
        internal const val KEY_MANAGEMENT_MODE = "management_mode"

        /** R13 management HTTPS port; see [managementPort]. SERVICE_RESTART. */
        internal const val KEY_MANAGEMENT_PORT = "management_port"

        /** R13 LAN idle auto-close window; see [managementIdleTimeoutMs]. LIVE. */
        internal const val KEY_MANAGEMENT_IDLE_TIMEOUT_MS = "management_idle_timeout_ms"

        /** Management is off by default; a fresh install never opens a socket. */
        internal const val DEFAULT_MANAGEMENT_MODE = "disabled"
        internal const val DEFAULT_MANAGEMENT_PORT = 8765
        internal const val DEFAULT_MANAGEMENT_IDLE_TIMEOUT_MS = 900_000L
    }
}

/**
 * Clamp shared by the [AppPrefs.yandexTtsSpeed] read and write paths. A
 * non-finite value (NaN/±Infinity) degrades to the service default; a finite
 * out-of-range value is clamped to the supported `[MIN_SPEED, MAX_SPEED]`.
 */
private fun clampYandexTtsSpeed(value: Float): Float =
    if (value.isFinite()) {
        value.coerceIn(YandexVoiceSpec.MIN_SPEED, YandexVoiceSpec.MAX_SPEED)
    } else {
        YandexVoiceSpec.DEFAULT_SPEED
    }
