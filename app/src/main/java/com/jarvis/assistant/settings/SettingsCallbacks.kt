package com.jarvis.assistant.settings

import com.jarvis.assistant.speech.SpeechBackend

/**
 * Callback contract the Settings screen uses to push user input out of the UI
 * layer. Every method is implemented by the host and wires input into the
 * [com.jarvis.assistant.util.CredentialsStore] and the live wake-word
 * [com.jarvis.assistant.audio.HybridWakeWordDetector] exposed via
 * [com.jarvis.assistant.di.GraphHolder] (when the assistant is running).
 *
 * Moved verbatim out of `SettingsActivity.kt` by the settings-redesign
 * foundation lane; the methods are unchanged. Its host implementations are now
 * [SettingsCallbacksReal] / [SettingsCallbacksStub], moved out of the Activity
 * by the FLIP lane.
 */
interface SettingsCallbacks {
    /** Persist the user-supplied provider credentials. */
    suspend fun onSaveCredentials(
        picovoiceKey: String,
        saluteId: String,
        saluteSecret: String,
        gigaChatId: String,
        gigaChatSecret: String,
        yandexApiKey: String,
    )

    /** The LLM backend changed: "gigachat" | "openai" | "yandex". */
    fun onLlmProviderSelected(type: String)

    /**
     * The speech backend changed (Sber / Yandex). Persisting is the caller's
     * job; the running graph keeps the old provider until the next service
     * start, which the card's hint states.
     */
    fun onSpeechBackendSelected(backend: SpeechBackend)

    /**
     * Persist the [OI]-compatible endpoint settings (url/model/key) and the
     * optional Yandex folder id (blank = auto-discover).
     */
    suspend fun onSaveLlmProviderSettings(
        baseUrl: String,
        model: String,
        apiKey: String,
        yandexFolderId: String,
    )

    /** The weather default city changed (blank = auto-detect via GPS). */
    fun onWeatherLocationSaved(location: String)

    /** The chosen wake-word model changed (`builtin` | `custom_bundled`). */
    fun onWakeWordSelected(modelId: String)

    /** FIXPLAN C: a validated custom Sherpa keyword was applied (blank = bundled Jarvis). */
    suspend fun onSherpaKeywordApplied(keyword: String)

    /** FIXPLAN B: the voice-stop toggle changed. */
    fun onVoiceStopToggled(enabled: Boolean)

    /** Porcupine sensitivity changed, range 0.0–1.0. */
    fun onSensitivityChanged(value: Float)

    /** The chosen wake-word engine changed ("porcupine" | "sherpa"). */
    fun onEngineSelected(engine: String)
}
