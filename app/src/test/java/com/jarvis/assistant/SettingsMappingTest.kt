package com.jarvis.assistant

import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.ui.SettingsMapping.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure setting ↔ control mappings (U1). `SettingsActivity` has no Robolectric
 * coverage, so these tests are the only safety net the extracted logic has —
 * which is exactly why it was extracted.
 */
class SettingsMappingTest {

    // ---- preferred music player ----

    @Test
    fun `both yandex package names map to the same radio`() {
        // The legacy package must not fall through to AUTO: an install that
        // stored it would silently lose its chosen player.
        assertEquals(Player.YANDEX, SettingsMapping.playerForPref("ru.yandex.music"))
        assertEquals(Player.YANDEX, SettingsMapping.playerForPref("com.yandex.music"))
    }

    @Test
    fun `known players map to their radios`() {
        assertEquals(Player.ZVUK, SettingsMapping.playerForPref("com.zvooq.openplay"))
        assertEquals(Player.VK, SettingsMapping.playerForPref("com.uma.musicvk"))
        assertEquals(Player.AUTO, SettingsMapping.playerForPref("auto"))
    }

    @Test
    fun `both vk package names map to the same radio`() {
        // The canonical applicationId plus the legacy com.vk.music value
        // older builds persisted. com.vk.music is VK Music's code namespace,
        // never an applicationId, but a stored pref may still hold it.
        assertEquals(Player.VK, SettingsMapping.playerForPref("com.uma.musicvk"))
        assertEquals(Player.VK, SettingsMapping.playerForPref("com.vk.music"))
    }

    @Test
    fun `writing a vk selection canonicalizes to the real package`() {
        assertEquals("com.uma.musicvk", SettingsMapping.playerPrefFor(Player.VK))
    }

    @Test
    fun `unknown or blank player degrades to auto`() {
        // A user-uninstalled or hand-edited value must not crash or stick.
        assertEquals(Player.AUTO, SettingsMapping.playerForPref("com.example.other"))
        assertEquals(Player.AUTO, SettingsMapping.playerForPref(""))
    }

    @Test
    fun `writing a legacy yandex selection canonicalizes it`() {
        assertEquals("ru.yandex.music", SettingsMapping.playerPrefFor(Player.YANDEX))
    }

    @Test
    fun `every player round-trips through pref and back`() {
        for (player in Player.entries) {
            assertEquals(player, SettingsMapping.playerForPref(SettingsMapping.playerPrefFor(player)))
        }
    }

    // ---- embedder cycle ----

    @Test
    fun `embedder cycles through all four modes and wraps`() {
        assertEquals("CLOUD", SettingsMapping.nextEmbedder("AUTO"))
        assertEquals("LOCAL", SettingsMapping.nextEmbedder("CLOUD"))
        assertEquals("OFF", SettingsMapping.nextEmbedder("LOCAL"))
        assertEquals("AUTO", SettingsMapping.nextEmbedder("OFF"))
    }

    @Test
    fun `embedder is case-insensitive`() {
        assertEquals("CLOUD", SettingsMapping.nextEmbedder("auto"))
    }

    @Test
    fun `unrecognized embedder restarts the cycle instead of sticking`() {
        // indexOf == -1 must not produce a crash or a stuck selector.
        assertEquals("AUTO", SettingsMapping.nextEmbedder("GARBAGE"))
    }

    @Test
    fun `cycling the embedder visits every mode without repeating`() {
        var current = "AUTO"
        val seen = mutableListOf(current)
        repeat(SettingsMapping.EMBEDDER_ORDER.size - 1) {
            current = SettingsMapping.nextEmbedder(current)
            seen += current
        }
        assertEquals(SettingsMapping.EMBEDDER_ORDER, seen)
    }

    // ---- follow-up window ----

    @Test
    fun `follow-up slider maps to the 2 to 12 second window`() {
        assertEquals(2_000L, SettingsMapping.followUpSeconds(0))
        assertEquals(12_000L, SettingsMapping.followUpSeconds(10))
        assertEquals(7_000L, SettingsMapping.followUpSeconds(5))
    }

    @Test
    fun `follow-up slider clamps out-of-range progress`() {
        assertEquals(2_000L, SettingsMapping.followUpSeconds(-5))
        assertEquals(12_000L, SettingsMapping.followUpSeconds(99))
    }

    // ---- quiet-hour steppers (wrap-around) ----

    @Test
    fun `quiet hour wraps forward past midnight`() {
        assertEquals(0, SettingsMapping.quietHour(23, 1))
    }

    @Test
    fun `quiet hour wraps backward past midnight`() {
        // The interesting case: `0 - 1` must be 23, not -1. The old inline
        // `(current + 23) % 24` only worked for a delta of exactly -1.
        assertEquals(23, SettingsMapping.quietHour(0, -1))
        assertEquals(22, SettingsMapping.quietHour(0, -2))
    }

    @Test
    fun `quiet hour stays in range for any delta`() {
        for (hour in 0..23) {
            for (delta in -50..50) {
                val result = SettingsMapping.quietHour(hour, delta)
                assertTrue("$hour + $delta = $result out of range", result in 0..23)
            }
        }
    }

    // ---- quota stepper ----

    @Test
    fun `quota clamps to the shipped range`() {
        assertEquals(1, SettingsMapping.quota(1, -1))
        assertEquals(5, SettingsMapping.quota(5, 1))
        assertEquals(3, SettingsMapping.quota(2, 1))
    }

    @Test
    fun `quota stays in range for any delta`() {
        for (value in 1..5) {
            for (delta in -10..10) {
                assertTrue(SettingsMapping.quota(value, delta) in 1..5)
            }
        }
    }

    // ---- voice id ----

    @Test
    fun `explicit mila radio wins over custom text`() {
        assertEquals("Mila", SettingsMapping.selectedVoiceId(isMilaSelected = true, customText = "Bys"))
    }

    @Test
    fun `custom voice is trimmed and blank falls back to mila`() {
        assertEquals("Bys", SettingsMapping.selectedVoiceId(false, "  Bys  "))
        assertEquals("Mila", SettingsMapping.selectedVoiceId(false, "   "))
    }

    // ---- engine visibility ----

    @Test
    fun `only sherpa hides the porcupine block`() {
        assertTrue(SettingsMapping.isSherpaEngine("sherpa"))
        assertFalse(SettingsMapping.isSherpaEngine("porcupine"))
        assertFalse(SettingsMapping.isSherpaEngine(""))
    }
}
