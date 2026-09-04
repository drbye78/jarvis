package com.jarvis.assistant.config

/**
 * Tunables for the assistant pipeline. All latency-critical knobs in one
 * place; no behavioral constants scattered through components.
 */
data class JarvisConfig(
    // Porcupine wake word
    val porcupineKeywordPath: String = "jarvis_ru.ppn",

    // Session
    val maxUtteranceMs: Long = 90_000,      // hard cap for one utterance
    val maxToolPasses: Int = 5,             // bounded tool loop

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
    val gigaChatModel: String = "GigaChat-Pro",

    // OAuth
    val oauthEndpoint: String = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth",
    val oauthRefreshThresholdMs: Long = 60_000,

    // TTS
    val ttsVoice: String = "Mila",
    val ttsSentenceTimeoutMs: Long = 20_000,
    val ttsDrainTimeoutMs: Long = 60_000,

    // Service watchdog
    val restartIntervalMs: Long = 15 * 60 * 1000L,

    // History
    val historyMaxMessages: Int = 20,

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
) {
    enum class Type { GIGACHAT, OPENAI_COMPAT }

    companion object {
        val DEFAULT = ProviderSettings(
            type = Type.GIGACHAT,
            openAiBaseUrl = "https://api.openai.com/v1",
            openAiModel = "gpt-4o-mini",
        )
    }
}
