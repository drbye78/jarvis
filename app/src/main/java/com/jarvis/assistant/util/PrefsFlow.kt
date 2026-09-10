package com.jarvis.assistant.util

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * COGNITIVE_PLAN 0.7: reactive StateFlow wrappers over [AppPrefs] for the
 * COGNITIVE switches.
 *
 * Scope note (dead-flow trim): this class ALSO used to carry the wake-word
 * and voice-stop/follow-up prefs as StateFlows — but NOTHING collected them:
 * live-toggling those settings actually rests on the explicit
 * `reconfigureWakeWord()` / `setFollowUpWindow()` call sites in Settings and
 * the service binder (the wake-word engine rebuild is a native-engine
 * construction that must fire ONCE per change, and wiring a second reactive
 * collector alongside the explicit call sites would double-fire it). Those
 * dead flows were removed; this class now carries exactly the consumed set —
 * the CognitiveCoordinator's switches (Phase 1), the behaviour layer
 * (Phase 2) and the semantic-recall selector (Phase 3).
 *
 * Every flow below emits the current value immediately and every change as
 * it happens (a [SharedPreferences.OnSharedPreferenceChangeListener]
 * fan-out). The Cognitive Core's switches are consumed reactively from day
 * one; a regression test asserting live-change semantics is part of the
 * definition of done for every new setting (AGENTS.md convention).
 *
 * Lifecycle: construction registers the listener; [close] unregisters it.
 * The graph-scoped instance lives as long as the process's graph.
 */
class PrefsFlow(private val appPrefs: AppPrefs) : SharedPreferences.OnSharedPreferenceChangeListener {

    private val prefs: SharedPreferences = appPrefs.rawPrefs()

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

    // --- COGNITIVE_PLAN Phase 3: semantic-recall selector (§12.4-3) --------

    private val _memoryEmbedder = MutableStateFlow(appPrefs.memoryEmbedder)
    val memoryEmbedder: StateFlow<String> = _memoryEmbedder.asStateFlow()

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
            AppPrefs.KEY_MEMORY_ENABLED -> _memoryEnabled.value = appPrefs.memoryEnabled
            AppPrefs.KEY_MEMORY_AUTO_EXTRACT -> _memoryAutoExtract.value = appPrefs.memoryAutoExtract
            AppPrefs.KEY_MEMORY_CLOUD_ENABLED -> _memoryCloudEnabled.value = appPrefs.memoryCloudEnabled
            AppPrefs.KEY_MEMORY_SENSITIVE_VISIBLE -> _memorySensitiveVisible.value = appPrefs.memorySensitiveVisible
            AppPrefs.KEY_BEHAVIOR_ENABLED -> _behaviorEnabled.value = appPrefs.behaviorEnabled
            AppPrefs.KEY_BEHAVIOR_QUIET_START -> _behaviorQuietStart.value = appPrefs.behaviorQuietStart
            AppPrefs.KEY_BEHAVIOR_QUIET_END -> _behaviorQuietEnd.value = appPrefs.behaviorQuietEnd
            AppPrefs.KEY_BEHAVIOR_DAILY_QUOTA -> _behaviorDailyQuota.value = appPrefs.behaviorDailyQuota
            AppPrefs.KEY_MEMORY_EMBEDDER -> _memoryEmbedder.value = appPrefs.memoryEmbedder
        }
    }
}
