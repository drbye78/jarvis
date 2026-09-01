package com.jarvis.assistant

import com.jarvis.assistant.media.SearchLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 regression: deep-link URIs must percent-encode spaces as %20.
 *
 * The old inline code used URLEncoder.encode, which emits `+` for spaces —
 * correct for HTML form bodies, WRONG in URIs: neither Uri.getQueryParameter
 * (query form) nor any path-segment consumer (https form) decodes `+` back to
 * a space, so «Кино Группа крови» reached the player as «Кино+Группа+крови»
 * and the search found nothing. Production passes Uri::encode; these tests
 * pin the URI STRUCTURE with a percent-encoder stand-in, and one test proves
 * the encoder's output is used verbatim (a `+`-emitting encoder would produce
 * a mangled link — the exact bug).
 */
class SearchLinksTest {

    /** RFC3986-ish stand-in for android.net.Uri.encode. */
    private val percentEncoder: (String) -> String = { it.replace(" ", "%20") }

    @Test
    fun `yandex scheme link uses the encoded query`() {
        val links = SearchLinks.searchUris("ru.yandex.music", "Кино Группа крови", percentEncoder)
        assertEquals("yandexmusic://search?query=Кино%20Группа%20крови", links[0])
    }

    @Test
    fun `both yandex package ids produce scheme link first then https`() {
        for (pkg in listOf("ru.yandex.music", "com.yandex.music")) {
            val links = SearchLinks.searchUris(pkg, "Bohemian Rhapsody", percentEncoder)
            assertEquals(2, links.size)
            assertEquals("yandexmusic://search?query=Bohemian%20Rhapsody", links[0])
            assertEquals("https://music.yandex.ru/search/Bohemian%20Rhapsody", links[1])
        }
    }

    @Test
    fun `encoder output is embedded verbatim - plus-encoding is exposed as mangled`() {
        // The bug itself: if the production encoder emits '+', the link is
        // wrong — SearchLinks must not "fix" or double-encode; the choice of
        // encoder (Uri.encode, not URLEncoder) is what the adapter pins.
        val formEncoder: (String) -> String = { it.replace(" ", "+") }
        val links = SearchLinks.searchUris("ru.yandex.music", "Bohemian Rhapsody", formEncoder)
        assertEquals("yandexmusic://search?query=Bohemian+Rhapsody", links[0])
        // i.e. the adapter MUST pass Uri::encode — a '+' link is unusable.
    }

    @Test
    fun `unknown player has no deep links`() {
        assertTrue(SearchLinks.searchUris("com.unknown.player", "X", percentEncoder).isEmpty())
    }

    @Test
    fun `zvuk intentionally has no unverified deep links`() {
        // Per-player honesty: zvuk.com's web-search URL shape is unverified
        // from the dev environment, so the cascade must fall through to the
        // browser/launch lanes rather than open a guess. When the on-device
        // check (RUNBOOK «Zvuk») confirms a shape, this test flips to pin it.
        assertTrue(SearchLinks.searchUris("com.zvooq.openplay", "Кино", percentEncoder).isEmpty())
    }

    @Test
    fun `russian multiword query is fully percent-encoded in the https form`() {
        val links = SearchLinks.searchUris("ru.yandex.music", "Кино Группа крови", percentEncoder)
        assertEquals("https://music.yandex.ru/search/Кино%20Группа%20крови", links[1])
    }
}
