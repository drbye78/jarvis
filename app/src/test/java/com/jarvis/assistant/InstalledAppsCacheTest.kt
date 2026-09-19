package com.jarvis.assistant

import com.jarvis.assistant.media.InstalledAppsCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-8: the installed-app enumeration walks every package (one
 * `getLaunchIntentForPackage` + label lookup each), and a music command
 * resolves the player at least once per turn. The cache must serve repeated
 * resolves from memory and refresh only after the TTL — pinned here by a
 * loader call-count assertion.
 */
class InstalledAppsCacheTest {

    @Test
    fun `enumeration is cached within the ttl`() {
        var now = 1_000L
        var loads = 0
        val cache = InstalledAppsCache(
            loader = {
                loads++
                listOf("ru.yandex.music" to "Яндекс Музыка")
            },
            ttlMs = 60_000,
            now = { now },
        )

        val first = cache.get()
        val second = cache.get()
        now += 59_999
        val third = cache.get()

        assertEquals(1, loads)
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun `enumeration refreshes once the ttl expires`() {
        var now = 0L
        var loads = 0
        val cache = InstalledAppsCache(
            loader = {
                loads++
                listOf("app.$loads" to "Player $loads")
            },
            ttlMs = 60_000,
            now = { now },
        )

        assertEquals(listOf("app.1" to "Player 1"), cache.get())
        now += 60_000
        val refreshed = cache.get()

        assertEquals(2, loads)
        assertEquals(listOf("app.2" to "Player 2"), refreshed)
    }

    @Test
    fun `the snapshot list is not re-copied per call`() {
        val loaded = listOf("ru.yandex.music" to "Яндекс Музыка")
        val cache = InstalledAppsCache(loader = { loaded }, ttlMs = 60_000, now = { 0L })

        assertTrue(cache.get() === cache.get())
    }
}
