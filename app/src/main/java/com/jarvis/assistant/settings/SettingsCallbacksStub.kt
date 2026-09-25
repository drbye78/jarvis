package com.jarvis.assistant.settings

import com.jarvis.assistant.speech.SpeechBackend
import timber.log.Timber

/**
 * No-op stand-in for [SettingsCallbacks]; every invocation is logged so a
 * pre-init settings tap is visible (P5.3).
 *
 * Moved out of `SettingsActivity.kt` by the FLIP lane (was the private
 * `object StubCallbacks`) so both hosts can hold it as the safe default until
 * [SettingsCallbacksReal] is constructed.
 */
object SettingsCallbacksStub : SettingsCallbacks {

    private fun notReady(name: String) {
        Timber.w("Settings: callback %s invoked before the graph is ready — ignored", name)
    }

    override suspend fun onSaveCredentials(
        picovoiceKey: String,
        saluteId: String,
        saluteSecret: String,
        gigaChatId: String,
        gigaChatSecret: String,
        yandexApiKey: String,
    ) {
        notReady("onSaveCredentials")
    }

    override fun onSpeechBackendSelected(backend: SpeechBackend) {
        notReady("onSpeechBackendSelected")
    }

    override fun onLlmProviderSelected(type: String) {
        notReady("onLlmProviderSelected")
    }

    override suspend fun onSaveLlmProviderSettings(
        baseUrl: String,
        model: String,
        apiKey: String,
        yandexFolderId: String,
    ) {
        notReady("onSaveLlmProviderSettings")
    }

    override fun onWeatherLocationSaved(location: String) {
        notReady("onWeatherLocationSaved")
    }

    override fun onWakeWordSelected(modelId: String) {
        notReady("onWakeWordSelected")
    }

    override suspend fun onSherpaKeywordApplied(keyword: String) {
        notReady("onSherpaKeywordApplied")
    }

    override fun onVoiceStopToggled(enabled: Boolean) {
        notReady("onVoiceStopToggled")
    }

    override fun onEngineSelected(engine: String) {
        notReady("onEngineSelected")
    }

    override fun onSensitivityChanged(value: Float) {
        notReady("onSensitivityChanged")
    }
}
