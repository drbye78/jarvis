package com.jarvis.assistant

import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.InMemoryVault
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression guard for C1: `ProviderSettings` must round-trip through
 * [AppPrefs.loadProviderSettings] with EVERY field intact.
 *
 * The original bug was a hand-rolled `ProviderSettings.DEFAULT.copy(...)` in
 * [com.jarvis.assistant.service.JarvisForegroundService] that copied only
 * `type` / `openAiBaseUrl` / `openAiModel`, silently dropping `gigaChatModel`
 * (so the Settings GigaChat-model radio was inert in production) and would
 * have dropped the Yandex fields too. This test fails on any partial copy of
 * that shape, and [loadProviderSettings] is now the single construction path.
 *
 * Setup mirrors [SpeechBackendPrefsTest]: a null context + in-memory vault /
 * prefs (the 0.7 test seam).
 */
class AppPrefsProviderSettingsTest {

    private fun newPrefs(
        prefs: android.content.SharedPreferences = FakeSharedPreferences(),
        vault: InMemoryVault = InMemoryVault(),
    ) = AppPrefs(context = null, vaultOverride = vault, prefsOverride = prefs)

    @Test
    fun `loadProviderSettings round-trips every field unchanged`() {
        val backing = FakeSharedPreferences()
        val appPrefs = newPrefs(backing)

        appPrefs.providerType = ProviderSettings.Type.YANDEX
        appPrefs.openAiBaseUrl = "https://example.test/v1"
        appPrefs.openAiModel = "custom-model"
        // Both non-default flavors: a partial hand-copy drops exactly these.
        appPrefs.gigaChatModel = "GigaChat-3-Ultra"
        appPrefs.yandexModel = "yandexgpt-5-lite"
        appPrefs.yandexFolderId = "b1g-folder-id"

        val loaded = appPrefs.loadProviderSettings()

        assertEquals(ProviderSettings.Type.YANDEX, loaded.type)
        assertEquals("https://example.test/v1", loaded.openAiBaseUrl)
        assertEquals("custom-model", loaded.openAiModel)
        assertEquals("GigaChat-3-Ultra", loaded.gigaChatModel)
        assertEquals("yandexgpt-5-lite", loaded.yandexModel)
        assertEquals("b1g-folder-id", loaded.yandexFolderId)

        // A fresh AppPrefs over the SAME store must observe it — this is the
        // service-restart path (the graph is rebuilt from prefs).
        assertEquals(loaded, newPrefs(backing).loadProviderSettings())
    }

    @Test
    fun `gigaChatModel set in Settings reaches the runtime provider config`() {
        // The precise C1 regression: a non-default GigaChat flavor must survive
        // the prefs → graph hand-off, not be reset to the default.
        val appPrefs = newPrefs()

        appPrefs.gigaChatModel = "GigaChat-3-Pro"

        assertEquals("GigaChat-3-Pro", appPrefs.loadProviderSettings().gigaChatModel)
    }

    @Test
    fun `every provider type round-trips through prefs`() {
        for (type in ProviderSettings.Type.entries) {
            val appPrefs = newPrefs()
            appPrefs.providerType = type
            assertEquals(type, appPrefs.providerType)
            assertEquals(type, appPrefs.loadProviderSettings().type)
        }
    }

    @Test
    fun `yandex model falls back to the default when unknown`() {
        val appPrefs = newPrefs()

        appPrefs.yandexModel = "not-a-real-model"

        assertEquals(ProviderSettings.DEFAULT_YANDEX_MODEL, appPrefs.yandexModel)
        assertEquals(ProviderSettings.DEFAULT_YANDEX_MODEL, appPrefs.loadProviderSettings().yandexModel)
    }

    @Test
    fun `yandex folder id defaults to blank and is allowed blank`() {
        val appPrefs = newPrefs()

        assertEquals("", appPrefs.loadProviderSettings().yandexFolderId)

        appPrefs.yandexFolderId = "some-folder"
        assertEquals("some-folder", appPrefs.loadProviderSettings().yandexFolderId)

        appPrefs.yandexFolderId = ""
        assertEquals("", appPrefs.loadProviderSettings().yandexFolderId)
    }
}
