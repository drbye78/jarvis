package com.jarvis.assistant.settings

import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The real [SettingsCallbacks]: wires Settings input into [CredentialsStore],
 * [AppPrefs] and the live wake-word engine / session exposed via
 * [GraphHolder] (when the assistant is running).
 *
 * Moved out of `SettingsActivity.kt` by the FLIP lane (was `inner class
 * RealCallbacks`); the bodies are verbatim, with two mechanical changes forced
 * by leaving the Activity:
 *
 *  - `appPrefs` is an injected [AppPrefs] instead of the Activity field;
 *  - `lifecycleScope.launch(Dispatchers.Default)` becomes [scope].launch(…) —
 *    the detail host passes its own `lifecycleScope`, so the coroutine still
 *    dies with the screen.
 *
 * ONE deliberate omission: the old `onEngineSelected` ended by calling the
 * Activity's `applyEngineVisibility(engine)`. That call is now owned by
 * `ListeningSettingsController` (which toggles its own blocks right after
 * invoking this callback), so keeping it here would be an inaccessible
 * Activity method — and the user-visible outcome is identical.
 *
 * The custom-`.ppn` picker is NOT part of this contract at all: it lives on the
 * frozen host seam ([SettingsHost.importCustomPpn]), which the listening
 * controller calls directly. The old `onLoadCustomPpn` callback was deleted once
 * it had no caller — a dead interface method that every implementor must stub is
 * pure maintenance cost.
 */
class SettingsCallbacksReal(
    private val prefs: AppPrefs,
    private val scope: CoroutineScope,
) : SettingsCallbacks {

    override suspend fun onSaveCredentials(
        picovoiceKey: String,
        saluteId: String,
        saluteSecret: String,
        gigaChatId: String,
        gigaChatSecret: String,
        yandexApiKey: String,
    ) {
        CredentialsStore.get().picovoiceKey = picovoiceKey
        CredentialsStore.get().saluteClientId = saluteId
        CredentialsStore.get().saluteClientSecret = saluteSecret
        CredentialsStore.get().gigaChatClientId = gigaChatId
        CredentialsStore.get().gigaChatClientSecret = gigaChatSecret
        CredentialsStore.get().yandexApiKey = yandexApiKey
        // Force a token refresh so a changed Picovoice/Sber key applies now.
        GraphHolder.graph?.tokenManager?.invalidate()
        // Apply a changed Picovoice key live to the wake-word engine (this
        // method is suspend, so reconfigure can be awaited directly).
        GraphHolder.graph?.reconfigureWakeWord()
    }

    override fun onSpeechBackendSelected(backend: SpeechBackend) {
        // Pref only. The composition root seals the backend at construction
        // (each provider owns its channel AND its auth scheme, so there is
        // no live path), which is exactly what the card's restart hint
        // tells the user.
        prefs.speechBackend = backend
    }

    override fun onLlmProviderSelected(type: String) {
        prefs.providerType = SettingsMapping.providerTypeFor(type)
    }

    override suspend fun onSaveLlmProviderSettings(
        baseUrl: String,
        model: String,
        apiKey: String,
        yandexFolderId: String,
    ) {
        prefs.openAiBaseUrl = baseUrl
        prefs.openAiModel = model.ifBlank { ProviderSettings.DEFAULT.openAiModel }
        prefs.openAiApiKey = apiKey
        prefs.yandexFolderId = yandexFolderId
    }

    override fun onWeatherLocationSaved(location: String) {
        // Pref only: the weather tool reads it live per turn, so no restart.
        prefs.weatherLocation = location
    }

    override fun onWakeWordSelected(modelId: String) {
        prefs.wakeWordModel = modelId
        scope.launch(Dispatchers.Default) {
            GraphHolder.graph?.reconfigureWakeWord()
        }
    }

    override suspend fun onSherpaKeywordApplied(keyword: String) {
        prefs.sherpaCustomKeyword = keyword
        GraphHolder.graph?.reconfigureWakeWord()
    }

    override fun onVoiceStopToggled(enabled: Boolean) {
        // COGNITIVE_PLAN 0.2: the pref alone is NOT enough. The stop phrase
        // is baked into the ENGINE keyword set at build time (Sherpa) and
        // into the dedicated stop lane's build requirement (Porcupine), so
        // the toggle must rebuild the live engine — the same path the
        // engine/model/sensitivity callbacks use. The pref re-read by the
        // session state collector only re-arms the LANE on the next state
        // change; without this rebuild, 3 of the 4 engine×toggle
        // combinations stayed stale until a restart.
        //
        // A5: the state collector fires on STATE CHANGE only, so re-arm the
        // lane for the current state too — otherwise enabling voice stop
        // while the assistant is THINKING/SPEAKING did nothing until the
        // turn ended (the rebuild's own tail re-arm reads the flag this
        // sets).
        GraphHolder.graph?.sessionManager?.reapplyVoiceStopLane()
        scope.launch(Dispatchers.Default) {
            GraphHolder.graph?.reconfigureWakeWord()
        }
    }

    override fun onEngineSelected(engine: String) {
        prefs.wakeWordEngine = engine
        // Block visibility is applied by ListeningSettingsController right after
        // this callback returns; the old Activity did it here only because the
        // controller did not exist yet.
        scope.launch(Dispatchers.Default) {
            GraphHolder.graph?.reconfigureWakeWord()
        }
    }

    override fun onSensitivityChanged(value: Float) {
        prefs.wakeSensitivity = value
        scope.launch(Dispatchers.Default) {
            GraphHolder.graph?.reconfigureWakeWord()
        }
    }
}
