package com.jarvis.assistant.config

/**
 * Tunables for the assistant pipeline. All latency-critical knobs in one
 * place; no behavioral constants scattered through components.
 */
data class JarvisConfig(
    // Porcupine wake word
    /**
     * REMEDIATION_PLAN P0.7: this default is a CONFIG PLACEHOLDER, not a
     * shipped asset — the repo intentionally does NOT bundle `jarvis_ru.ppn`
     * (see RUNBOOK: .ppn keywords are user-supplied via Settings and are
     * bound to the user's Picovoice key). Building a Porcupine engine
     * against this default path fails by design; the detector surfaces
     * actionable guidance (enter key / select a custom .ppn) instead.
     */
    val porcupineKeywordPath: String = "jarvis_ru.ppn",

    // Session
    val maxUtteranceMs: Long = 90_000, // hard cap for one utterance
    val maxToolPasses: Int = 5, // bounded tool loop

    // ASR
    val asrMaxRetries: Int = 2,
    val asrStreamDeadlineMs: Long = 90_000,

    // LLM
    val llmTimeoutMs: Long = 45_000,
    val gigaChatTemperature: Double = 0.7,
    val gigaChatMaxTokens: Int = 2048,

    /**
     * G4 (dialogue audit): transient LLM failures are retried when the stream
     * produced ZERO output (so no sentence can be spoken twice). Applies to
     * IOExceptions, 5xx/429 [com.jarvis.assistant.llm.LlmHttpException] and
     * zero-output timeouts. 4xx never retries.
     */
    val llmMaxRetries: Int = 1,

    /** Base for the linear retry backoff (first retry waits this long). */
    val llmRetryBackoffMs: Long = 800,
    val gigaChatEndpoint: String = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions",

    /**
     * The unified GigaChat v2 endpoint (`api.giga.chat`, live-verified 2026-09).
     *
     * This is a SEPARATE constant from [gigaChatEndpoint] on purpose:
     * [com.jarvis.assistant.cognitive.embed.GigaChatEmbedder] derives its URL
     * by stripping `/chat/completions` and appending `/embeddings`, so
     * repointing the legacy constant would silently move embeddings too.
     *
     * The native contract differs from the legacy one on every axis: `content`
     * is an array of parts, `tools`/`tool_config` replace `functions`/`function_call`,
     * the response envelope is `messages[]` (not `choices[]`), and the stream
     * carries named `event:` lines. Server-side built-ins (`web_search`) are
     * only reachable here.
     */
    val gigaChatNativeEndpoint: String = "https://api.giga.chat/v2/chat/completions",

    // OAuth
    val oauthEndpoint: String = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth",
    val oauthRefreshThresholdMs: Long = 60_000,

    // TTS
    val ttsVoice: String = "Mila",
    val ttsSentenceTimeoutMs: Long = 20_000,
    val ttsDrainTimeoutMs: Long = 60_000,

    // Yandex SpeechKit v3 (alternative speech backend; API-key auth)
    /**
     * v3 STT and TTS are SEPARATE hosts (unlike Salute's single
     * `smartspeech.sber.ru`), so the graph owns one channel per service.
     */
    val yandexSttEndpoint: String = "stt.api.cloud.yandex.net:443",
    val yandexTtsEndpoint: String = "tts.api.cloud.yandex.net:443",
    /**
     * Yandex TTS voice used when the user has not chosen one. `marina` is the
     * documented default ru-RU voice; the Settings card exposes the full
     * ru-RU catalog ([com.jarvis.assistant.speech.tts.VoiceCatalog]).
     */
    val yandexTtsVoice: String = "marina",

    // Service watchdog
    val restartIntervalMs: Long = 15 * 60 * 1000L,

    // History
    val historyMaxMessages: Int = 20,

    /**
     * COGNITIVE_PLAN 1.9: on-disk retention for the messages table. Larger
     * than the LLM window — the memory core's opt-in backfill works on the
     * retained recent dialogue. Storage cost is trivial; nothing here is
     * ever auto-sent anywhere (extraction is gated + queued).
     */
    val historyRetentionMessages: Int = 200,

    /**
     * COGNITIVE_PLAN §8.2: the habit-eligible tool allowlist. Read-mostly
     * and music tools only — habits for `setVolume`/`lockScreen` would be
     * noise. Everything outside this set is never mined into a rule.
     */
    val habitEligibleTools: Set<String> = setOf(
        "playMusic",
        "getWeather",
        "getNowPlaying",
        "listPlaylists",
        "searchLibrary",
    ),

    /**
     * Y5 (dialogue audit): hard char budget for the history window. A crude
     * chars/4 ≈ tokens estimate — no tokenizer dependency — that keeps the
     * request inside the model context even with verbose tool results.
     * 0 disables the budget. The newest message is always kept (truncated
     * if it alone overflows) so the turn is never answered contextless.
     */
    val historyMaxChars: Int = 24_000,

    // Audio pre-roll (M8): how much recent mic audio the ring buffer keeps so
    // the first words are not clipped between wake word and ASR stream open.
    val preRollMs: Long = DEFAULT_PRE_ROLL_MS,

    // Barge-in policy (M7): interrupting TTS playback requires a repeated wake
    // word within [bargeInRepeatWindowMs] unless [bargeInSingleShot] is set.
    val bargeInRepeatWindowMs: Long = 1_200,
    val bargeInSingleShot: Boolean = false,

    // Speech gRPC endpoint (saluteChannel target for SaluteSpeech ASR + TTS).
    // Renamed from the misleading `llmEndpoint` (audit A1): the LLM URL is
    // [gigaChatEndpoint] / the provider base URL — this field NEVER touched
    // the LLM lane.
    val saluteGrpcEndpoint: String = "smartspeech.sber.ru:443",

    // Phase 5 (M7 mitigation): pause external music at session start for a
    // clean listening window. Default OFF: music stops and does NOT auto-resume —
    // the user says «продолжи» when they want it back. (AEC-aware: in SOFTWARE
    // mode the wake word can also survive music via the canceller, but the
    // explicit pause remains the most reliable path — the canceller is
    // experimental NLMS, not AEC3.)
    val pauseMusicOnWake: Boolean = false,

    /**
     * Voice stop (FIXPLAN B): saying «стоп» / "stop" while the assistant
     * THINKS or SPEAKS cancels the active turn without the wake word.
     * Default ON; toggleable in Settings. The stop phrase is spotted by the
     * same on-device KWS engine — no network, no second model.
     */
    val voiceStopEnabled: Boolean = true,
) {
    companion object {
        /** Single source of truth for the pre-roll default ([AudioPipeline] references it). */
        const val DEFAULT_PRE_ROLL_MS = 3_000L
    }
}

/**
 * User-facing provider configuration (Settings screen). Persisted in plain
 * prefs; secrets (the OpenAI-compatible API key) live in the SecretVault.
 *
 * Audit A2: the dead `wakeSensitivity` duplicate was removed — the wake
 * sensitivity slider reads/writes [com.jarvis.assistant.util.AppPrefs]
 * directly (the live source the engine reconfigure path consumes).
 */
data class ProviderSettings(
    val type: Type,
    val openAiBaseUrl: String,
    val openAiModel: String,
    /** GigaChat-3 flavor for the native client; one of [GIGACHAT_MODELS]. */
    val gigaChatModel: String = DEFAULT_GIGACHAT_MODEL,
) {
    enum class Type { GIGACHAT, OPENAI_COMPAT }

    companion object {
        val DEFAULT = ProviderSettings(
            type = Type.GIGACHAT,
            openAiBaseUrl = "https://api.openai.com/v1",
            openAiModel = "gpt-4o-mini",
        )

        /**
         * The GigaChat-3 flavors offered in Settings (live-verified on
         * `api.giga.chat` 2026-09). Lightning is the default because a voice
         * assistant optimises for latency; Ultra is the most capable. The
         * GigaChat-2 family also works (including web search) but is not
         * offered — this build targets the current generation.
         */
        val GIGACHAT_MODELS: List<String> = listOf(
            "GigaChat-3-Lightning",
            "GigaChat-3-Pro",
            "GigaChat-3-Ultra",
        )

        const val DEFAULT_GIGACHAT_MODEL: String = "GigaChat-3-Lightning"
    }
}
