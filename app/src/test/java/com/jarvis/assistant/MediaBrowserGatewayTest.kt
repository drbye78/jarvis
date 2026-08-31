package com.jarvis.assistant

import com.jarvis.assistant.media.BrowserMediaItem
import com.jarvis.assistant.media.BrowserResultMatcher
import com.jarvis.assistant.media.BrowserServiceInfo
import com.jarvis.assistant.media.BrowserSession
import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MediaBrowserGateway
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.media.SearchCommand
import com.jarvis.assistant.media.VoiceQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 3: the MediaBrowser lane — S0 (deterministic mediaId play from scored
 * search results) and S2 (session-token cold start) inside the cascade, the
 * library tools (listPlaylists / searchLibrary / playLibraryItem), result
 * scoring, and bind hygiene (every attempt disconnects exactly once). All
 * against pure fakes: the Android adapter is compile-gated separately.
 */
class MediaBrowserGatewayTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeBrowserHandle(
        override val packageName: String,
    ) : MediaControllerHandle {
        var np: NowPlaying = NowPlaying()

        override fun snapshot(): NowPlaying = np
        override fun capabilities(): MediaCapabilities = MediaCapabilities.UNKNOWN
        override fun playFromSearch(query: String): Boolean = true
        override fun play(): Boolean = true
        override fun pause(): Boolean = true
        override fun skipToNext(): Boolean = true
        override fun skipToPrevious(): Boolean = true
        override fun stop(): Boolean = true
    }

    private class FakeBrowserSession(
        override val packageName: String,
        private val rootId: String = "root",
        /** null root id = the service connected but browsing is unavailable. */
        val searchResults: List<BrowserMediaItem>? = null,
        val childrenByParent: Map<String, List<BrowserMediaItem>> = emptyMap(),
        val searchSupported: Boolean = true,
    ) : BrowserSession {
        val fakeHandle = FakeBrowserHandle(packageName)
        var controllerHandle: MediaControllerHandle = fakeHandle

        /** When set, replaces the deterministic mediaId play (ignore-tests). */
        var playFromMediaIdBehavior: (() -> Boolean)? = null

        var disconnectCalls = 0
        var searched: String? = null
        var childrenOf: String? = null
        var playedMediaId: String? = null

        override fun root(): String = rootId

        override suspend fun search(query: String, timeoutMs: Long): List<BrowserMediaItem>? {
            searched = query
            return if (searchSupported) searchResults else null
        }

        override suspend fun children(parentId: String, timeoutMs: Long, max: Int): List<BrowserMediaItem>? {
            childrenOf = parentId
            return childrenByParent[parentId]?.take(max)
        }

        override fun controller(): MediaControllerHandle = controllerHandle

        override fun playFromMediaId(mediaId: String): Boolean {
            playedMediaId = mediaId
            playFromMediaIdBehavior?.let { return it() }
            // Deterministic play: the item we named starts playing, with the
            // metadata the search result advertised.
            val item = searchResults?.firstOrNull { it.mediaId == mediaId }
                ?: childrenByParent.values.flatten().firstOrNull { it.mediaId == mediaId }
            fakeHandle.np = NowPlaying(
                title = item?.title ?: "Трек",
                artist = item?.artist,
                state = NowPlaying.STATE_PLAYING,
                positionMs = 0,
            )
            return true
        }

        override fun disconnect() { disconnectCalls++ }
    }

    private class FakeBrowserGateway(
        /** Package ids with an installed MediaBrowserService. */
        val servicePackages: List<String> = listOf("ru.yandex.music"),
        /** When set, connect() hands out this session; null = refused/timeout. */
        var sessionFactory: (() -> FakeBrowserSession?)? = { null },
    ) : MediaBrowserGateway {
        var connectCalls = 0

        override fun discover(): List<BrowserServiceInfo> =
            servicePackages.map { BrowserServiceInfo(it, "Player $it") }

        override suspend fun connect(packageName: String, timeoutMs: Long): BrowserSession? {
            connectCalls++
            return sessionFactory?.invoke()
        }
    }

    private class FakeGateway(
        var listenerAccess: Boolean = true,
    ) : MediaGateway {
        override fun hasNotificationListenerAccess() = listenerAccess
        override fun activeControllers(): List<MediaControllerHandle> = emptyList()
        override fun dispatchMediaKey(keyCode: Int) = Unit
        override fun openAppSearch(app: MediaAppInfo, query: String) = false
        override fun launchApp(app: MediaAppInfo) = false
    }

    private fun orchestrator(
        gateway: FakeGateway,
        browser: FakeBrowserGateway,
    ) = MusicPlaybackOrchestrator(
        gateway,
        MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
        browser,
        budgets = MusicPlaybackOrchestrator.Budgets(
            verifyPollMs = 50, verifyTotalMs = 500,
            coldStartPollMs = 50, coldStartTotalMs = 300,
            legacyWaitTotalMs = 300,
            browserConnectTimeoutMs = 200, browserSearchTimeoutMs = 200,
        ),
    )

    // ------------------------------------------------------------------
    // S0: browser search → deterministic mediaId play
    // ------------------------------------------------------------------

    @Test
    fun `S0 browser search hit plays by mediaId and verifies`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(
                BrowserMediaItem("id1", "Bohemian Rhapsody - Remaster", "Queen"),
                BrowserMediaItem("id2", "Совсем другая песня", "Никто"),
            ),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })
        val gw = FakeGateway()

        val out = orchestrator(gw, browser).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("browser_media_id", out.strategy)
        assertEquals("id1", session.playedMediaId)
        assertEquals("Bohemian Rhapsody", session.searched)
        // One attempt = one bind, always released.
        assertEquals(1, browser.connectCalls)
        assertEquals(1, session.disconnectCalls)
    }

    @Test
    fun `S0 skips when no result reaches the strong threshold`() = runTest {
        // Search returns only weak matches: playing any of them would be a
        // guess — the cascade must fall through to S2 instead.
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(BrowserMediaItem("id1", "Другая песня", "Другие")),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })
        val gw = FakeGateway()

        val out = orchestrator(gw, browser).playSearchQuery("Bohemian Rhapsody", null)

        // S2 dispatched playFromSearch on the token controller; UNKNOWN caps
        // are permissive, but the session's handle never plays anything
        // matching, so the cascade ends honestly (no app launch possible).
        assertNull(session.playedMediaId)
        assertEquals(1, session.disconnectCalls)
        assertFalse(out.status == MusicPlaybackOrchestrator.Status.PLAYING)
    }

    @Test
    fun `S0 ignores non-playable results`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(
                BrowserMediaItem("id1", "Bohemian Rhapsody", "Queen", playable = false),
            ),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).playSearchQuery("Bohemian Rhapsody", null)

        assertNull(session.playedMediaId)
        assertFalse(out.status == MusicPlaybackOrchestrator.Status.PLAYING)
    }

    @Test
    fun `S0 not verified when the player ignores playFromMediaId`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(BrowserMediaItem("id1", "Bohemian Rhapsody", "Queen")),
        )
        // The old track keeps playing — the mediaId command was ignored.
        session.fakeHandle.np = NowPlaying(
            title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 60_000,
        )
        session.playFromMediaIdBehavior = { true } // accepted but ignored
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals("id1", session.playedMediaId) // dispatched…
        assertFalse(out.status == MusicPlaybackOrchestrator.Status.PLAYING) // …but not verified
        assertEquals(1, session.disconnectCalls)
    }

    // ------------------------------------------------------------------
    // S2: session-token cold start
    // ------------------------------------------------------------------

    @Test
    fun `S2 token controller dispatches playFromSearch and verifies`() = runTest {
        val session = FakeBrowserSession("ru.yandex.music", searchSupported = false)
        // The token controller HONORS playFromSearch. Implemented as a full
        // handle (NOT `by` delegation): the interface-default
        // playFromSearchStructured binds to the DELEGATE, silently bypassing
        // any playFromSearch override in the expression body.
        session.controllerHandle = object : MediaControllerHandle {
            override val packageName get() = "ru.yandex.music"
            override fun snapshot(): NowPlaying = session.fakeHandle.np
            override fun capabilities(): MediaCapabilities = MediaCapabilities.UNKNOWN

            override fun playFromSearchStructured(command: SearchCommand): Boolean {
                session.fakeHandle.np = NowPlaying(
                    title = "Bohemian Rhapsody", artist = "Queen",
                    state = NowPlaying.STATE_PLAYING, positionMs = 0,
                )
                return true
            }

            override fun playFromSearch(query: String): Boolean = true
            override fun play(): Boolean = true
            override fun pause(): Boolean = true
            override fun skipToNext(): Boolean = true
            override fun skipToPrevious(): Boolean = true
            override fun stop(): Boolean = true
        }
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("browser_cold_start", out.strategy)
        assertEquals(1, session.disconnectCalls)
    }

    @Test
    fun `browser refused - cascade falls through to launch cold start`() = runTest {
        val browser = FakeBrowserGateway(sessionFactory = { null })
        val gw = object : MediaGateway {
            override fun hasNotificationListenerAccess() = true
            override fun activeControllers(): List<MediaControllerHandle> = emptyList()
            override fun dispatchMediaKey(keyCode: Int) = Unit
            override fun openAppSearch(app: MediaAppInfo, query: String) = false
            var launched = false
            override fun launchApp(app: MediaAppInfo): Boolean {
                launched = true
                return true
            }
        }

        val out = MusicPlaybackOrchestrator(
            gw,
            MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
            browser,
            budgets = MusicPlaybackOrchestrator.Budgets(
                verifyPollMs = 50, verifyTotalMs = 500,
                coldStartPollMs = 50, coldStartTotalMs = 300,
                legacyWaitTotalMs = 300,
            ),
        ).playSearchQuery("Bohemian Rhapsody", null)

        // Refused browser + failed cold start (no session ever appears) +
        // no deep link (openAppSearch false) → honest launch-only outcome,
        // proving the cascade CONTINUED past the refused bind.
        assertEquals(MusicPlaybackOrchestrator.Status.APP_OPENED, out.status)
        assertTrue(gw.launched)
    }

    @Test
    fun `browser lane works WITHOUT notification listener access`() = runTest {
        // The whole point of the token path: no permission needed.
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(BrowserMediaItem("id1", "Bohemian Rhapsody", "Queen")),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })
        val gw = FakeGateway(listenerAccess = false)

        val out = orchestrator(gw, browser).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("browser_media_id", out.strategy)
    }

    @Test
    fun `app without a browser service skips the lane entirely`() = runTest {
        val browser = FakeBrowserGateway(servicePackages = listOf("com.other.player"))
        val session = FakeBrowserSession("ru.yandex.music")
        browser.sessionFactory = { session }

        orchestrator(FakeGateway(), browser).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(0, browser.connectCalls) // never even tried to bind
    }

    // ------------------------------------------------------------------
    // Library tools
    // ------------------------------------------------------------------

    @Test
    fun `listPlaylists returns root children as items`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            childrenByParent = mapOf(
                "root" to listOf(
                    BrowserMediaItem("p1", "Для тренировки", browsable = true),
                    BrowserMediaItem("p2", "Кино: лучшие", browsable = true),
                ),
            ),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).listPlaylists(null)

        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        assertEquals("root", session.childrenOf)
        assertEquals(2, out.items?.size)
        assertEquals("Для тренировки", out.items?.first()?.title)
        assertEquals(1, session.disconnectCalls)
    }

    @Test
    fun `listPlaylists without a browser gateway answers honestly`() = runTest {
        val out = MusicPlaybackOrchestrator(
            FakeGateway(),
            MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
            budgets = MusicPlaybackOrchestrator.Budgets(
                verifyPollMs = 50, verifyTotalMs = 500,
                coldStartPollMs = 50, coldStartTotalMs = 300,
            ),
        ).listPlaylists(null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.detail.contains("библиотеку"))
    }

    @Test
    fun `searchLibrary returns playable results`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(
                BrowserMediaItem("t1", "Группа крови", "Кино"),
                BrowserMediaItem("t2", "Section: Кино", null, playable = false, browsable = true),
            ),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).searchLibrary("Группа крови", null)

        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        assertEquals("Группа крови", session.searched)
        assertEquals(listOf("t1"), out.items?.map { it.mediaId })
    }

    @Test
    fun `searchLibrary unsupported answers with the fallback instruction`() = runTest {
        val session = FakeBrowserSession("ru.yandex.music", searchSupported = false)
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).searchLibrary("Кино", null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.detail.contains("не поддерживает поиск"))
        assertEquals(1, session.disconnectCalls)
    }

    @Test
    fun `playLibraryItem with a title hint verifies by score`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(BrowserMediaItem("t1", "Группа крови", "Кино")),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser)
            .playLibraryItem("t1", "Группа крови", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("browser_media_id", out.strategy)
        assertEquals(1, session.disconnectCalls)
    }

    @Test
    fun `playLibraryItem without a title accepts any playing state`() = runTest {
        val session = FakeBrowserSession("ru.yandex.music")
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).playLibraryItem("whatever-id", null, null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
    }

    // ------------------------------------------------------------------
    // BrowserResultMatcher
    // ------------------------------------------------------------------

    @Test
    fun `matcher scores titles and artists`() {
        val vq = VoiceQuery.clean("Группа крови", artist = "Кино")!!
        assertEquals(
            1.0,
            BrowserResultMatcher.score(BrowserMediaItem("i", "Группа крови", "Кино"), vq),
            0.001,
        )
        assertEquals(
            0.0,
            BrowserResultMatcher.score(BrowserMediaItem("i", "Другое", "Другие"), vq),
            0.001,
        )
    }

    @Test
    fun `bestMatch picks the strongest playable item`() {
        val vq = VoiceQuery.clean("Bohemian Rhapsody")!!
        val items = listOf(
            BrowserMediaItem("weak", "Bohemian", "Someone"),      // partial title
            BrowserMediaItem("strong", "Bohemian Rhapsody", "Queen"),
            BrowserMediaItem("blocked", "Bohemian Rhapsody", "Queen", playable = false),
        )
        assertEquals("strong", BrowserResultMatcher.bestMatch(items, vq)?.mediaId)
    }

    @Test
    fun `bestMatch returns null below the threshold`() {
        val vq = VoiceQuery.clean("Группа крови")!!
        assertNull(
            BrowserResultMatcher.bestMatch(
                listOf(BrowserMediaItem("i", "Совсем другое", "Другие")),
                vq,
            ),
        )
    }

    // ------------------------------------------------------------------
    // Diagnostics
    // ------------------------------------------------------------------

    @Test
    fun `browser diagnostics table formats services`() {
        val lines = com.jarvis.assistant.media.MediaDiagnostics.browserTable(
            listOf(BrowserServiceInfo("ru.yandex.music", "Яндекс Музыка")),
        )
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("ru.yandex.music"))
        assertTrue(
            com.jarvis.assistant.media.MediaDiagnostics.browserTable(emptyList())[0]
                .contains("no MediaBrowserService"),
        )
    }

    @Test
    fun `structured slots flow into the browser search query`() = runTest {
        val session = FakeBrowserSession(
            "ru.yandex.music",
            searchResults = listOf(BrowserMediaItem("id1", "Группа крови", "Кино")),
        )
        val browser = FakeBrowserGateway(sessionFactory = { session })

        val out = orchestrator(FakeGateway(), browser).playSearchQuery(
            rawQuery = "Группа крови", artist = "Кино", album = null,
            playlist = null, genre = null, appHint = null,
        )

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        // Flat text (title + artist) is what the service searches.
        assertEquals("Группа крови Кино", session.searched)
    }
}
