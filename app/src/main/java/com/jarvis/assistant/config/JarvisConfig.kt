package com.jarvis.assistant.config

/**
 * Tunables for the assistant pipeline. All latency-critical knobs in one
 * place; no behavioral constants scattered through components.
 */
data class JarvisConfig(
    // Porcupine wake word
    val porcupineSensitivity: Float = 0.6f,
    val porcupineKeywordPath: String = "jarvis_ru.ppn",

    // Session
    val wakeWordCooldownMs: Long = 600,
    val maxUtteranceMs: Long = 90_000,      // hard cap for one utterance
    val maxToolPasses: Int = 5,             // bounded tool loop

    // ASR
    val asrMaxRetries: Int = 2,
    val asrStreamDeadlineMs: Long = 90_000,

    // LLM
    val llmTimeoutMs: Long = 45_000,
    val gigaChatTemperature: Double = 0.7,
    val gigaChatMaxTokens: Int = 2048,
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

    // Audio pre-roll (M8): how much recent mic audio the ring buffer keeps so
    // the first words are not clipped between wake word and ASR stream open.
    val preRollMs: Long = DEFAULT_PRE_ROLL_MS,

    // Barge-in policy (M7): interrupting TTS playback requires a repeated wake
    // word within [bargeInRepeatWindowMs] unless [bargeInSingleShot] is set.
    val bargeInRepeatWindowMs: Long = 1_200,
    val bargeInSingleShot: Boolean = false,
) {
    companion object {
        /** Single source of truth for the pre-roll default ([AudioPipeline] references it). */
        const val DEFAULT_PRE_ROLL_MS = 3_000L
    }
}

/**
 * User-facing provider configuration (Settings screen). Persisted in plain
 * prefs except the API key, which lives in SecurePrefs.
 */
data class ProviderSettings(
    val type: Type,
    val openAiBaseUrl: String,
    val openAiModel: String,
    val wakeSensitivity: Float,
) {
    enum class Type { GIGACHAT, OPENAI_COMPAT }

    companion object {
        val DEFAULT = ProviderSettings(
            type = Type.GIGACHAT,
            openAiBaseUrl = "https://api.openai.com/v1",
            openAiModel = "gpt-4o-mini",
            wakeSensitivity = 0.6f,
        )
    }
}
