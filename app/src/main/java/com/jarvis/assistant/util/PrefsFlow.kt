package com.jarvis.assistant.util

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * COGNITIVE_PLAN 0.7: reactive StateFlow wrappers over [AppPrefs].
 *
 * The re-audit's must-fix defect class was "config frozen at graph build
 * time": [com.jarvis.assistant.contracts.WakeWordRequest] captured
 * `voiceStopEnabled` once at initialization and `onVoiceStopToggled` was a
 * no-op, so 3 of the 4 engine×toggle combinations silently ignored the
 * Settings switch. Reading prefs through a lambda per call (the FIXPLAN B
 * fix) is correct but PULL-based — every consumer must remember to re-read,
 * and nothing tells a consumer that a value CHANGED.
 *
 * [PrefsFlow] is the PUSH-based foundation: every wake-word, voice-stop and
 * follow-up pref is exposed as a [StateFlow] that emits the current value
 * immediately and every change as it happens (a
 * [SharedPreferences.OnSharedPreferenceChangeListener] fan-out). The
 * Cognitive Core's switches (Phase 1+) are consumed reactively from day
 * one; a regression test asserting live-change semantics is part of the
 * definition of done for every new setting (AGENTS.md convention).
 *
 * Lifecycle: construction registers the listener; [close] unregisters it.
 * The graph-scoped instance lives as long as the process's graph.
 */
class PrefsFlow(private val appPrefs: AppPrefs) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs: SharedPreferences = appPrefs.rawPrefs()

    // --- Wake-word configuration (drives AppGraph.buildWakeRequest) ---------

    private val _wakeWordEngine = MutableStateFlow(appPrefs.wakeWordEngine)
    val wakeWordEngine: StateFlow<String> = _wakeWordEngine.asStateFlow()

    private val _wakeWordModel = MutableStateFlow(appPrefs.wakeWordModel)
    val wakeWordModel: StateFlow<String> = _wakeWordModel.asStateFlow()

    private val _wakeSensitivity = MutableStateFlow(appPrefs.wakeSensitivity)
    val wakeSensitivity: StateFlow<Float> = _wakeSensitivity.asStateFlow()

    private val _sherpaCustomKeyword = MutableStateFlow(appPrefs.sherpaCustomKeyword)
    val sherpaCustomKeyword: StateFlow<String> = _sherpaCustomKeyword.asStateFlow()

    private val _sherpaOnnxPath = MutableStateFlow(appPrefs.sherpaOnnxPath)
    val sherpaOnnxPath: StateFlow<String> = _sherpaOnnxPath.asStateFlow()

    private val _customWakeWordPath = MutableStateFlow(appPrefs.customWakeWordPath)
    val customWakeWordPath: StateFlow<String> = _customWakeWordPath.asStateFlow()

    // --- Voice stop + follow-up window (consumed by the session layer) ------

    private val _voiceStopEnabled = MutableStateFlow(appPrefs.voiceStopEnabled)
    val voiceStopEnabled: StateFlow<Boolean> = _voiceStopEnabled.asStateFlow()

    private val _followUpEnabled = MutableStateFlow(appPrefs.followUpEnabled)
    val followUpEnabled: StateFlow<Boolean> = _followUpEnabled.asStateFlow()

    private val _followUpWindowMs = MutableStateFlow(appPrefs.followUpWindowMs)
    val followUpWindowMs: StateFlow<Long> = _followUpWindowMs.asStateFlow()

    // --- COGNITIVE_PLAN Phase 1: memory switches (§9.2/§12.4) ----------------
    // Every switch below is user-configurable (owner sign-off) and read per
    // turn by the CognitiveCoordinator — a Settings toggle applies live, and
    // PrefsFlowTest asserts the push for each new key (the AGENTS.md rule).

    private val _memoryEnabled = MutableStateFlow(appPrefs.memoryEnabled)
    val memoryEnabled: StateFlow<Boolean> = _memoryEnabled.asStateFlow()

    private val _memoryAutoExtract = MutableStateFlow(appPrefs.memoryAutoExtract)
    val memoryAutoExtract: StateFlow<Boolean> = _memoryAutoExtract.asStateFlow()

    private val _memoryCloudEnabled = MutableStateFlow(appPrefs.memoryCloudEnabled)
    val memoryCloudEnabled: StateFlow<Boolean> = _memoryCloudEnabled.asStateFlow()

    private val _memorySensitiveVisible = MutableStateFlow(appPrefs.memorySensitiveVisible)
    val memorySensitiveVisible: StateFlow<Boolean> = _memorySensitiveVisible.asStateFlow()

    // --- COGNITIVE_PLAN Phase 2: behaviour switches (§8/§12.4-1) ------------

    private val _behaviorEnabled = MutableStateFlow(appPrefs.behaviorEnabled)
    val behaviorEnabled: StateFlow<Boolean> = _behaviorEnabled.asStateFlow()

    private val _behaviorQuietStart = MutableStateFlow(appPrefs.behaviorQuietStart)
    val behaviorQuietStart: StateFlow<Int> = _behaviorQuietStart.asStateFlow()

    private val _behaviorQuietEnd = MutableStateFlow(appPrefs.behaviorQuietEnd)
    val behaviorQuietEnd: StateFlow<Int> = _behaviorQuietEnd.asStateFlow()

    private val _behaviorDailyQuota = MutableStateFlow(appPrefs.behaviorDailyQuota)
    val behaviorDailyQuota: StateFlow<Int> = _behaviorDailyQuota.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    /** Unregister the listener (graph shutdown; harmless to call twice). */
    fun close() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        // The AppPrefs getters re-read the (already updated) backing store;
        // only the key that actually changed is re-read and pushed.
        when (key) {
            AppPrefs.KEY_WAKE_ENGINE -> _wakeWordEngine.value = appPrefs.wakeWordEngine
            AppPrefs.KEY_WAKE_MODEL -> _wakeWordModel.value = appPrefs.wakeWordModel
            AppPrefs.KEY_WAKE_SENSITIVITY -> _wakeSensitivity.value = appPrefs.wakeSensitivity
            AppPrefs.KEY_SHERPA_KEYWORD -> _sherpaCustomKeyword.value = appPrefs.sherpaCustomKeyword
            AppPrefs.KEY_SHERPA_ONNX -> _sherpaOnnxPath.value = appPrefs.sherpaOnnxPath
            AppPrefs.KEY_CUSTOM_WAKE_PATH -> _customWakeWordPath.value = appPrefs.customWakeWordPath
            AppPrefs.KEY_VOICE_STOP -> _voiceStopEnabled.value = appPrefs.voiceStopEnabled
            AppPrefs.KEY_FOLLOW_UP_ENABLED -> _followUpEnabled.value = appPrefs.followUpEnabled
            AppPrefs.KEY_FOLLOW_UP_WINDOW_MS -> _followUpWindowMs.value = appPrefs.followUpWindowMs
            AppPrefs.KEY_MEMORY_ENABLED -> _memoryEnabled.value = appPrefs.memoryEnabled
            AppPrefs.KEY_MEMORY_AUTO_EXTRACT -> _memoryAutoExtract.value = appPrefs.memoryAutoExtract
            AppPrefs.KEY_MEMORY_CLOUD_ENABLED -> _memoryCloudEnabled.value = appPrefs.memoryCloudEnabled
            AppPrefs.KEY_MEMORY_SENSITIVE_VISIBLE -> _memorySensitiveVisible.value = appPrefs.memorySensitiveVisible
            AppPrefs.KEY_BEHAVIOR_ENABLED -> _behaviorEnabled.value = appPrefs.behaviorEnabled
            AppPrefs.KEY_BEHAVIOR_QUIET_START -> _behaviorQuietStart.value = appPrefs.behaviorQuietStart
            AppPrefs.KEY_BEHAVIOR_QUIET_END -> _behaviorQuietEnd.value = appPrefs.behaviorQuietEnd
            AppPrefs.KEY_BEHAVIOR_DAILY_QUOTA -> _behaviorDailyQuota.value = appPrefs.behaviorDailyQuota
        }
    }
}
