package com.jarvis.assistant

import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.PrefsFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN 0.7: reactive settings. The "config frozen at graph build"
 * bug class (the dead voice-stop toggle) is banned by construction: every
 * wake-word / voice-stop / follow-up pref must push its change to any
 * collector within one setter call — no restart, no polling, no re-read
 * discipline required from the consumer.
 */
class PrefsFlowTest {

    @Test
    fun `initial values are surfaced immediately`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean(AppPrefs.KEY_VOICE_STOP, false).commit()
        prefs.edit().putString(AppPrefs.KEY_WAKE_ENGINE, "porcupine").commit()

        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            assertFalse(flow.voiceStopEnabled.value)
            assertEquals("porcupine", flow.wakeWordEngine.value)
            assertEquals("custom_bundled", flow.wakeWordModel.value)
            assertEquals(0.6f, flow.wakeSensitivity.value)
            assertEquals(5_000L, flow.followUpWindowMs.value)
        } finally {
            flow.close()
        }
    }

    @Test
    fun `a setter pushes the change to the flow live`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            assertTrue(flow.voiceStopEnabled.value)
            prefs.edit().putBoolean(AppPrefs.KEY_VOICE_STOP, false).commit()
            assertFalse("voice-stop toggle must apply without a restart", flow.voiceStopEnabled.value)

            prefs.edit().putString(AppPrefs.KEY_WAKE_ENGINE, "porcupine").commit()
            assertEquals("porcupine", flow.wakeWordEngine.value)

            prefs.edit().putFloat(AppPrefs.KEY_WAKE_SENSITIVITY, 0.9f).commit()
            assertEquals(0.9f, flow.wakeSensitivity.value)

            prefs.edit().putLong(AppPrefs.KEY_FOLLOW_UP_WINDOW_MS, 8_000L).commit()
            assertEquals(8_000L, flow.followUpWindowMs.value)

            prefs.edit().putString(AppPrefs.KEY_SHERPA_KEYWORD, "computer").commit()
            assertEquals("computer", flow.sherpaCustomKeyword.value)
        } finally {
            flow.close()
        }
    }

    @Test
    fun `memory switches surface defaults`() {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            // COGNITIVE_PLAN §12.4 defaults: memory ON, autoExtract OFF
            // (eval-gated), cloud ON, sensitive visible-but-marked.
            assertTrue(flow.memoryEnabled.value)
            assertFalse(flow.memoryAutoExtract.value)
            assertTrue(flow.memoryCloudEnabled.value)
            assertTrue(flow.memorySensitiveVisible.value)
        } finally {
            flow.close()
        }
    }

    @Test
    fun `memory switches push live`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            // The plan-principle-5 live-toggle contract, applied to every new
            // Phase 1 setting (AGENTS.md: definition of done per setting).
            prefs.edit().putBoolean(AppPrefs.KEY_MEMORY_ENABLED, false).commit()
            assertFalse("memory kill switch must apply without a restart", flow.memoryEnabled.value)

            prefs.edit().putBoolean(AppPrefs.KEY_MEMORY_AUTO_EXTRACT, true).commit()
            assertTrue(flow.memoryAutoExtract.value)

            prefs.edit().putBoolean(AppPrefs.KEY_MEMORY_CLOUD_ENABLED, false).commit()
            assertFalse(flow.memoryCloudEnabled.value)

            prefs.edit().putBoolean(AppPrefs.KEY_MEMORY_SENSITIVE_VISIBLE, false).commit()
            assertFalse(flow.memorySensitiveVisible.value)
        } finally {
            flow.close()
        }
    }

    @Test
    fun `unrelated key changes are ignored without harm`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            assertTrue(flow.voiceStopEnabled.value)
            prefs.edit().putString(AppPrefs.KEY_TTS_VOICE, "Baya").commit()
            prefs.edit().putString(AppPrefs.KEY_MUSIC_PLAYER, "ru.yandex.music").commit()
            // No crash and no spurious change on the wrapped flows:
            assertTrue(flow.voiceStopEnabled.value)
            assertEquals(5_000L, flow.followUpWindowMs.value)
        } finally {
            flow.close()
        }
    }

    @Test
    fun `close unregisters the listener - changes stop propagating`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        flow.close()
        prefs.edit().putBoolean(AppPrefs.KEY_VOICE_STOP, false).commit()
        assertTrue("after close() the flow is frozen", flow.voiceStopEnabled.value)

        // Double-close must be safe (shutdown paths are best-effort).
        flow.close()
    }

    @Test
    fun `change is observable by a fresh collector within one suspend step`() = runBlocking {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            prefs.edit().putBoolean(AppPrefs.KEY_FOLLOW_UP_ENABLED, true).commit()
            withTimeout(1_000) {
                // first() resolves from the StateFlow's current value — a
                // late subscriber never misses the latest state (the
                // live-toggle contract Phase 1 relies on).
                assertTrue(flow.followUpEnabled.first())
            }
            assertEquals(true, flow.followUpEnabled.value)
        } finally {
            flow.close()
        }
    }
}

// ---------------------------------------------------------------------------
// COGNITIVE_PLAN 2.6/§12.4-1: the behaviour switches push live (the same
// no-restart contract as every other pref — the AGENTS.md convention).
// ---------------------------------------------------------------------------

class PrefsFlowBehaviorTest {

    @Test
    fun `behavior switches push live`() {
        val prefs = FakeSharedPreferences()
        val flow = PrefsFlow(AppPrefs(context = null, prefsOverride = prefs))
        try {
            // Defaults (§12.4-1: OFF; quiet 23→8; quota 2).
            assertFalse(flow.behaviorEnabled.value)
            assertEquals(23, flow.behaviorQuietStart.value)
            assertEquals(8, flow.behaviorQuietEnd.value)
            assertEquals(2, flow.behaviorDailyQuota.value)

            prefs.edit().putBoolean(AppPrefs.KEY_BEHAVIOR_ENABLED, true).commit()
            prefs.edit().putInt(AppPrefs.KEY_BEHAVIOR_QUIET_START, 22).commit()
            prefs.edit().putInt(AppPrefs.KEY_BEHAVIOR_QUIET_END, 9).commit()
            prefs.edit().putInt(AppPrefs.KEY_BEHAVIOR_DAILY_QUOTA, 4).commit()

            assertEquals(true, flow.behaviorEnabled.value)
            assertEquals(22, flow.behaviorQuietStart.value)
            assertEquals(9, flow.behaviorQuietEnd.value)
            assertEquals(4, flow.behaviorDailyQuota.value)
        } finally {
            flow.close()
        }
    }
}
