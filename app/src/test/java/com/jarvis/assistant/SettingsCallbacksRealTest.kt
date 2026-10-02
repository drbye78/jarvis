package com.jarvis.assistant

import com.jarvis.assistant.settings.SettingsCallbacksReal
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE 6 regression: the "voice stop" toggle used to rebuild the engine but
 * never persist [AppPrefs.voiceStopEnabled], so the pref silently reverted to
 * the stored default. [SettingsCallbacksReal] is the SINGLE writer — the
 * listening controller only sets the switch state and reads the pref back — so
 * these tests pin the write and its durability across an AppPrefs re-read.
 *
 * GraphHolder is null here, so the live-engine rebuild/re-arm are no-ops; this
 * test is deliberately scoped to persistence ownership.
 */
class SettingsCallbacksRealTest {

    @Test
    fun `onVoiceStopToggled persists the pref in both directions`() {
        val shared = FakeSharedPreferences()
        val prefs = AppPrefs(context = null, prefsOverride = shared)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val callbacks = SettingsCallbacksReal(prefs, scope)
            // Default is ON; turning it OFF must persist OFF.
            assertTrue("voice stop defaults ON", prefs.voiceStopEnabled)
            callbacks.onVoiceStopToggled(false)
            assertFalse(
                "voice stop OFF must be persisted, not just rebuilt",
                prefs.voiceStopEnabled,
            )
            // And back ON.
            callbacks.onVoiceStopToggled(true)
            assertTrue("voice stop ON must be persisted", prefs.voiceStopEnabled)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `onVoiceStopToggled writes the underlying preference key`() {
        val shared = FakeSharedPreferences()
        val prefs = AppPrefs(context = null, prefsOverride = shared)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            SettingsCallbacksReal(prefs, scope).onVoiceStopToggled(false)
            // A fresh AppPrefs over the SAME backing store must see the value —
            // proving it was written to storage, not merely held in memory.
            val reloaded = AppPrefs(context = null, prefsOverride = shared)
            assertFalse(
                "the voice-stop toggle must survive an AppPrefs re-read",
                reloaded.voiceStopEnabled,
            )
        } finally {
            scope.cancel()
        }
    }
}
