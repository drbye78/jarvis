package com.jarvis.assistant

import com.jarvis.assistant.tools.AppAliases
import com.jarvis.assistant.tools.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM rules for [AppAliases], the spoken-name → installed-package
 * resolver behind `openApp`. Regression: the live device has «VK Музыка»
 * installed as `com.uma.musicvk` with a LATIN label, so a Cyrillic query or
 * an ASR transliteration («викей») never matched the old
 * `label.contains(query)` check.
 */
class AppAliasesTest {

    private val installed = listOf(
        InstalledApp("ru.yandex.music", "Яндекс Музыка"),
        InstalledApp("com.zvooq.openplay", "Звук"),
        InstalledApp("com.uma.musicvk", "VK Музыка"),
        InstalledApp("com.example.player", "Foo Player"),
    )

    @Test
    fun `exact normalized label matches`() {
        assertEquals("com.example.player", AppAliases.resolve("Foo Player", installed)?.packageName)
    }

    @Test
    fun `case and whitespace variant of a label matches`() {
        assertEquals(
            "com.example.player",
            AppAliases.resolve("  FOO   player  ", installed)?.packageName,
        )
    }

    @Test
    fun `cyrillic vk muzyka resolves to the vk package`() {
        assertEquals("com.uma.musicvk", AppAliases.resolve("ВК Музыка", installed)?.packageName)
    }

    @Test
    fun `latin vk muzyka resolves to the vk package`() {
        assertEquals("com.uma.musicvk", AppAliases.resolve("VK Музыка", installed)?.packageName)
    }

    @Test
    fun `asr transliteration vikey muzyka resolves to the vk package`() {
        assertEquals("com.uma.musicvk", AppAliases.resolve("викей музыка", installed)?.packageName)
    }

    @Test
    fun `vkontakte muzyka resolves to the vk package`() {
        assertEquals("com.uma.musicvk", AppAliases.resolve("вконтакте музыка", installed)?.packageName)
    }

    @Test
    fun `zvuk resolves to the zvuk package`() {
        assertEquals("com.zvooq.openplay", AppAliases.resolve("Звук", installed)?.packageName)
        assertEquals("com.zvooq.openplay", AppAliases.resolve("zvuk", installed)?.packageName)
    }

    @Test
    fun `yandex muzyka resolves to the yandex package`() {
        assertEquals("ru.yandex.music", AppAliases.resolve("Яндекс Музыка", installed)?.packageName)
        assertEquals("ru.yandex.music", AppAliases.resolve("yandex music", installed)?.packageName)
        assertEquals("ru.yandex.music", AppAliases.resolve("яндексмузыка", installed)?.packageName)
    }

    @Test
    fun `alias only matches when the target package is installed`() {
        val withoutVk = installed.filterNot { it.packageName == "com.uma.musicvk" }
        assertNull(AppAliases.resolve("ВК Музыка", withoutVk))
    }

    @Test
    fun `nonexistent name resolves to null`() {
        assertNull(AppAliases.resolve("нет такого приложения", installed))
    }

    @Test
    fun `blank query resolves to null`() {
        assertNull(AppAliases.resolve("   ", installed))
        assertNull(AppAliases.resolve("", installed))
    }
}
