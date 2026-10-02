package com.jarvis.assistant

import com.jarvis.assistant.media.MusicPlayerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The voice → pref mapping for the default music player. Pinned values match
 * `SettingsMapping.playerPrefFor` (the Settings «Музыка» radio), so a spoken
 * choice writes exactly what the radio writes.
 */
class MusicPlayerChoiceTest {

    @Test
    fun `yandex names map to the canonical yandex package`() {
        listOf("Яндекс", "яндекс музыка", "ЯНДЕКС МУЗЫКА", "Yandex Music", "  yandex  ").forEach {
            assertEquals(it, MusicPlayerChoice.YANDEX_MUSIC, MusicPlayerChoice.prefValue(it))
        }
    }

    @Test
    fun `zvuk names map to the zvuk package`() {
        listOf("Звук", "звук музыка", "СберЗвук", "zvuk").forEach {
            assertEquals(it, MusicPlayerChoice.ZVUK, MusicPlayerChoice.prefValue(it))
        }
    }

    @Test
    fun `vk names map to the vk package`() {
        listOf("ВК", "вк музыка", "VK", "vk музыка", "ВКонтакте").forEach {
            assertEquals(it, MusicPlayerChoice.VK_MUSIC, MusicPlayerChoice.prefValue(it))
        }
    }

    @Test
    fun `auto phrases reset to automatic`() {
        listOf("авто", "Автоматически", "по умолчанию", "сброс", "сбрось", "верни авто", "auto", "default").forEach {
            assertEquals(it, MusicPlayerChoice.AUTO, MusicPlayerChoice.prefValue(it))
        }
    }

    @Test
    fun `whitespace and case are normalized`() {
        assertEquals(MusicPlayerChoice.ZVUK, MusicPlayerChoice.prefValue("  ЗВУК   МУЗЫКА  "))
        assertEquals(MusicPlayerChoice.VK_MUSIC, MusicPlayerChoice.prefValue("\tVK\nМузыка "))
        assertEquals(MusicPlayerChoice.AUTO, MusicPlayerChoice.prefValue("  ПО УМОЛЧАНИЮ "))
    }

    @Test
    fun `known player with a default phrase still selects the player`() {
        // The LLM may pass the whole spoken phrase as the slot; «по умолчанию»
        // must NOT win over a named brand in the same string.
        assertEquals(
            MusicPlayerChoice.YANDEX_MUSIC,
            MusicPlayerChoice.prefValue("плеер по умолчанию — Яндекс Музыка"),
        )
    }

    @Test
    fun `unknown names return null`() {
        listOf("spotify", "спам", "", "   ", "апельсин").forEach {
            assertNull(it, MusicPlayerChoice.prefValue(it))
        }
    }
}
