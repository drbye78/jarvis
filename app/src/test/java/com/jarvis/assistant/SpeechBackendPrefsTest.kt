package com.jarvis.assistant

import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.InMemoryVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deepwork Phase 3 seam tests: the speech-backend pref and the per-backend
 * voice plumbing that [com.jarvis.assistant.di.AppGraph] consumes at
 * construction.
 *
 * The load-bearing property here is PROVIDER ISOLATION. The two backends have
 * disjoint voice namespaces and disjoint credential slots, so a bug that let
 * one provider's pref leak into the other's path would not fail to compile —
 * it would ship a Sber voice id to Yandex (or read an empty key) and only
 * surface as a silent synthesis failure on a real device. Each of those
 * crossings is pinned below.
 */
class SpeechBackendPrefsTest {

    private fun newPrefs(
        prefs: android.content.SharedPreferences = FakeSharedPreferences(),
        vault: InMemoryVault = InMemoryVault(),
    ) = AppPrefs(context = null, vaultOverride = vault, prefsOverride = prefs)

    // ---- SpeechBackend parsing -------------------------------------------

    @Test
    fun `persisted backend values round-trip`() {
        for (backend in SpeechBackend.entries) {
            assertEquals(
                backend,
                SpeechBackend.fromPref(SpeechBackend.toPref(backend)),
            )
        }
    }

    @Test
    fun `the persisted backend strings are stable literals`() {
        // These are the on-disk contract: an install that stored "yandex"
        // must keep resolving to Yandex across app updates, so the raw values
        // are pinned rather than derived from the enum names.
        assertEquals("sber", SpeechBackend.toPref(SpeechBackend.SBER))
        assertEquals("yandex", SpeechBackend.toPref(SpeechBackend.YANDEX))
        assertEquals(SpeechBackend.YANDEX, SpeechBackend.fromPref("yandex"))
        assertEquals(SpeechBackend.SBER, SpeechBackend.fromPref("sber"))
    }

    @Test
    fun `unknown, blank and absent backend values degrade to the default`() {
        // A corrupt/legacy pref must never leave the app with no speech
        // backend at all — it falls back to Sber, the shipped default.
        assertEquals(SpeechBackend.SBER, SpeechBackend.fromPref(null))
        assertEquals(SpeechBackend.SBER, SpeechBackend.fromPref(""))
        assertEquals(SpeechBackend.SBER, SpeechBackend.fromPref("   "))
        assertEquals(SpeechBackend.SBER, SpeechBackend.fromPref("gigachat"))
        assertEquals(SpeechBackend.SBER, SpeechBackend.DEFAULT)
    }

    @Test
    fun `backend parsing is case- and whitespace-insensitive`() {
        assertEquals(SpeechBackend.YANDEX, SpeechBackend.fromPref("Yandex"))
        assertEquals(SpeechBackend.YANDEX, SpeechBackend.fromPref("  YANDEX  "))
    }

    // ---- AppPrefs persistence --------------------------------------------

    @Test
    fun `speech backend defaults to Sber and persists through prefs`() {
        val prefs = FakeSharedPreferences()
        val appPrefs = newPrefs(prefs)

        assertEquals(SpeechBackend.SBER, appPrefs.speechBackend)

        appPrefs.speechBackend = SpeechBackend.YANDEX
        assertEquals(SpeechBackend.YANDEX, appPrefs.speechBackend)

        // A fresh AppPrefs over the SAME backing store must observe it —
        // this is the restart path (the graph is rebuilt from prefs).
        assertEquals(SpeechBackend.YANDEX, newPrefs(prefs).speechBackend)
    }

    @Test
    fun `yandex voice defaults to marina and keeps its own slot`() {
        val appPrefs = newPrefs()

        assertEquals("marina", appPrefs.yandexTtsVoice)
        assertEquals("", appPrefs.yandexTtsRole)

        appPrefs.yandexTtsVoice = "alena"
        appPrefs.yandexTtsRole = "good"

        assertEquals("alena", appPrefs.yandexTtsVoice)
        assertEquals("good", appPrefs.yandexTtsRole)
    }

    @Test
    fun `the Sber and Yandex voice prefs never alias each other`() {
        val appPrefs = newPrefs()

        appPrefs.ttsVoice = "Mila"
        appPrefs.yandexTtsVoice = "ermil"

        assertEquals("Mila", appPrefs.ttsVoice)
        assertEquals("ermil", appPrefs.yandexTtsVoice)
        assertNotEquals(appPrefs.ttsVoice, appPrefs.yandexTtsVoice)
    }

    @Test
    fun `the yandex API key is stored in the vault, not in plain prefs`() {
        val vault = InMemoryVault()
        val appPrefs = newPrefs(vault = vault)

        appPrefs.yandexApiKey = "  secret-key  "

        // Trimmed on write, and readable through the vault-backed accessor.
        assertEquals("secret-key", appPrefs.yandexApiKey)
        assertEquals("secret-key", vault.getString("yandex_api_key"))
        // It must NOT have leaked into the non-secret pref file.
        assertTrue(
            "the API key must never land in SharedPreferences",
            appPrefs.rawPrefs().all.values.none { it == "secret-key" },
        )
    }

    @Test
    fun `the yandex API key uses a slot distinct from the openai key`() {
        val vault = InMemoryVault()
        val appPrefs = newPrefs(vault = vault)

        appPrefs.yandexApiKey = "yandex-secret"
        appPrefs.openAiApiKey = "openai-secret"

        assertEquals("yandex-secret", appPrefs.yandexApiKey)
        assertEquals("openai-secret", appPrefs.openAiApiKey)
    }

    // ---- Voice catalog ---------------------------------------------------

    @Test
    fun `the yandex catalog is non-empty, unique and contains the default`() {
        val voices = VoiceCatalog.YANDEX_VOICES
        assertTrue("the Yandex voice list must not be empty", voices.isNotEmpty())
        assertEquals(
            "duplicate voice ids would render two identical dropdown rows",
            voices.size,
            voices.toSet().size,
        )
        assertTrue(
            "the config default voice must be selectable in the UI",
            "marina" in voices,
        )
        assertTrue("voice ids must not carry stray whitespace", voices.none { it != it.trim() })
    }

    @Test
    fun `the Sber and Yandex voice sets are disjoint`() {
        val sber = VoiceCatalog.PRESETS.map { it.id.lowercase() }.toSet()
        val yandex = VoiceCatalog.YANDEX_VOICES.map { it.lowercase() }.toSet()
        assertEquals(
            "a shared id would make the active backend ambiguous",
            emptySet<String>(),
            sber intersect yandex,
        )
    }

    @Test
    fun `suggested yandex roles are non-blank and unique`() {
        assertTrue(VoiceCatalog.YANDEX_ROLES.isNotEmpty())
        assertEquals(
            VoiceCatalog.YANDEX_ROLES.size,
            VoiceCatalog.YANDEX_ROLES.toSet().size,
        )
        assertTrue(VoiceCatalog.YANDEX_ROLES.none { it.isBlank() })
    }
}
