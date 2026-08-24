package com.jarvis.assistant.config

data class JarvisConfig(
    // Porcupine
    val porcupineSensitivity: Float = 0.6f,
    val porcupineKeywordPath: String = "jarvis_ru.ppn",
    // VAD
    val vadSilenceDurationMs: Int = 300,
    val vadSpeechDurationMs: Int = 50,
    val vadSilenceFrames: Int = 25,
    // Session
    val wakeWordCooldownMs: Long = 600,
    val llmTimeoutMs: Long = 30_000,
    val asrTimeoutMs: Long = 60_000,
    val asrMaxRetries: Int = 2,
    // API
    val gigaChatEndpoint: String = "https://gigachat.devices.sberbank.ru/api/v1/chat/completions",
    val gigaChatModel: String = "GigaChat-Pro",
    val gigaChatTemperature: Double = 0.7,
    val gigaChatMaxTokens: Int = 2048,
    val oauthEndpoint: String = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth",
    val oauthRefreshThresholdMs: Long = 60_000,
    // TTS
    val ttsVoice: String = "Mila",
    // RESTART
    val restartIntervalMs: Long = 15 * 60 * 1000L,
    // History
    val historyMaxMessages: Int = 20,
)