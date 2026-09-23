package com.jarvis.assistant

import com.jarvis.assistant.media.MusicAppCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Resolution rules for [MusicAppCatalog], including the Zvuk
 * preferred-player chain (production: Settings «Музыка» card):
 *
 *  hint > label > preferred > auto-priority > label-keyword heuristic
 */
class MusicAppCatalogTest {

    private val allPlayers = listOf(
        "ru.yandex.music" to "Яндекс Музыка",
        "com.zvooq.openplay" to "Звук",
        "com.uma.musicvk" to "VK Музыка",
    )

    // ------------------------------------------------------------------
    // Auto priority (no preference, no hint)
    // ------------------------------------------------------------------

    @Test
    fun `auto priority prefers Yandex Music first`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("ru.yandex.music", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `auto falls back to Zvuk when Yandex absent`() {
        val catalog = MusicAppCatalog({ listOf("com.zvooq.openplay" to "Звук") })
        assertEquals("com.zvooq.openplay", catalog.resolve(null)?.packageName)
    }

    // ------------------------------------------------------------------
    // Preferred player (Settings «Музыка» card)
    // ------------------------------------------------------------------

    @Test
    fun `preferred Zvuk beats auto Yandex priority`() {
        val catalog = MusicAppCatalog({ allPlayers }, preferredPackage = { "com.zvooq.openplay" })
        assertEquals("com.zvooq.openplay", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `preferred player applies only when installed`() {
        val catalog = MusicAppCatalog(
            { listOf("ru.yandex.music" to "Яндекс Музыка") },
            preferredPackage = { "com.zvooq.openplay" },
        )
        // Zvuk preferred but NOT installed → degrade to auto priority honestly.
        assertEquals("ru.yandex.music", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `preferred null means auto`() {
        val catalog = MusicAppCatalog({ allPlayers }, preferredPackage = { null })
        assertEquals("ru.yandex.music", catalog.resolve(null)?.packageName)
    }

    // ------------------------------------------------------------------
    // Voice hint (LLM 'app' slot) — always beats the preference
    // ------------------------------------------------------------------

    @Test
    fun `explicit hint Zvuk beats preferred Yandex`() {
        val catalog = MusicAppCatalog({ allPlayers }, preferredPackage = { "ru.yandex.music" })
        assertEquals("com.zvooq.openplay", catalog.resolve("Звук")?.packageName)
    }

    @Test
    fun `explicit hint Yandex beats preferred Zvuk`() {
        val catalog = MusicAppCatalog({ allPlayers }, preferredPackage = { "com.zvooq.openplay" })
        assertEquals("ru.yandex.music", catalog.resolve("яндекс музыка")?.packageName)
    }

    @Test
    fun `zvuk hint resolves via brand token`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("com.zvooq.openplay", catalog.resolve("звук")?.packageName)
        assertEquals("com.zvooq.openplay", catalog.resolve("zvuk")?.packageName)
    }

    @Test
    fun `sber-zvuk compound hint still resolves by substring`() {
        val catalog = MusicAppCatalog({ allPlayers })
        // "сберзвук" contains "звук"/"сберзвук" tokens — resolution must not
        // depend on word boundaries.
        assertEquals("com.zvooq.openplay", catalog.resolve("сбер звук")?.packageName)
    }

    @Test
    fun `vk brand hints resolve to the real VK package`() {
        val catalog = MusicAppCatalog({ allPlayers })
        // The Cyrillic token is the only path: "вк" cannot match the Latin
        // label "VK Музыка", so this pins the brand-token table. Regression:
        // it used to target com.vk.music, which is not an applicationId.
        assertEquals("com.uma.musicvk", catalog.resolve("вк")?.packageName)
        assertEquals("com.uma.musicvk", catalog.resolve("vk")?.packageName)
    }

    @Test
    fun `code namespace com vk music is not a known player package`() {
        // com.vk.music is VK Music's internal code namespace, never an
        // applicationId. A brand hint must not match it (labels without a
        // music keyword isolate the brand table from the label heuristic),
        // while the real package com.uma.musicvk does.
        assertNull(MusicAppCatalog({ listOf("com.vk.music" to "VK") }).resolve("вк"))
        assertEquals(
            "com.uma.musicvk",
            MusicAppCatalog({ listOf("com.uma.musicvk" to "VK") }).resolve("вк")?.packageName,
        )
    }

    // ------------------------------------------------------------------
    // Generic-token collision (regression): a hint containing "музык" AND
    // another brand's marker must go to that brand, not to Yandex (whose
    // token list includes the generic "музык"). The playMusic schema
    // suggests 'VK Музыка' as an app value, so this collision is reachable
    // straight from the LLM.
    // ------------------------------------------------------------------

    @Test
    fun `vk full label hint resolves to VK not Yandex`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("com.uma.musicvk", catalog.resolve("VK Музыка")?.packageName)
    }

    @Test
    fun `hint with vk marker and word muzyka stays VK`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("com.uma.musicvk", catalog.resolve("музыка вк")?.packageName)
    }

    @Test
    fun `bare muzyka hint still resolves Yandex`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("ru.yandex.music", catalog.resolve("музыка")?.packageName)
    }

    @Test
    fun `yandex full label hint stays Yandex`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("ru.yandex.music", catalog.resolve("Яндекс Музыка")?.packageName)
    }

    @Test
    fun `zvuk marker with word muzyka stays Zvuk`() {
        val catalog = MusicAppCatalog({ allPlayers })
        assertEquals("com.zvooq.openplay", catalog.resolve("музыка звук")?.packageName)
    }

    @Test
    fun `unknown hint falls through to label match then preference`() {
        val catalog = MusicAppCatalog({ allPlayers }, preferredPackage = { "com.zvooq.openplay" })
        // "Что-то" matches no label → falls to the preferred player.
        assertEquals("com.zvooq.openplay", catalog.resolve("Что-то")?.packageName)
    }

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    fun `no players installed resolves to null`() {
        val catalog = MusicAppCatalog({ emptyList() }, preferredPackage = { "com.zvooq.openplay" })
        assertNull(catalog.resolve(null))
        assertNull(catalog.resolve("звук"))
    }

    @Test
    fun `label keyword heuristic catches unknown music app`() {
        val catalog = MusicAppCatalog({ listOf("com.unknown.x" to "My Music Player") })
        assertEquals("com.unknown.x", catalog.resolve(null)?.packageName)
    }
}
