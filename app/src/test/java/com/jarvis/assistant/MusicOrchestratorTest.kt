package com.jarvis.assistant

import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.media.MediaKey
import com.jarvis.assistant.tools.MusicTools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MUSIC lane tests: the strategy cascade is pure Kotlin over fakes, so every
 * branch (verified start, ignored playFromSearch, cold start, deep-link
 * fallback, transport fallbacks, app resolution) is covered here.
 */
class MusicOrchestratorTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeHandle(
        override val packageName: String,
        var np: NowPlaying = NowPlaying(),
        /** What happens when the assistant sends playFromSearch. */
        var onPlayFromSearch: (FakeHandle, String) -> Unit = { h, q ->
            h.np = NowPlaying(
                title = q, artist = "Queen",
                state = NowPlaying.STATE_PLAYING, positionMs = 0,
            )
        },
    ) : MediaControllerHandle {
        var lastSearched: String? = null
        var playCalls = 0
        var pauseCalls = 0
        var nextCalls = 0
        var prevCalls = 0
        var stopCalls = 0

        override fun snapshot(): NowPlaying = np
        override fun playFromSearch(query: String): Boolean {
            lastSearched = query
            onPlayFromSearch(this, query)
            return true
        }

        override fun play(): Boolean { playCalls++; return true }
        override fun pause(): Boolean { pauseCalls++; return true }
        override fun skipToNext(): Boolean { nextCalls++; return true }
        override fun skipToPrevious(): Boolean { prevCalls++; return true }
        override fun stop(): Boolean { stopCalls++; return true }
    }

    private class FakeGateway(
        var listenerAccess: Boolean = true,
        var searchOpens: Boolean = true,
        var launchWorks: Boolean = true,
        /** Polls of activeControllers() before a launched app posts its session. */
        var sessionAfterLaunchPolls: Int = 3,
        /** playFromSearch behavior of the session a cold start creates. */
        var launchedSessionBehavior: (FakeHandle, String) -> Unit = { h, q ->
            h.np = NowPlaying(
                title = q, artist = "Queen",
                state = NowPlaying.STATE_PLAYING, positionMs = 0,
            )
        },
    ) : MediaGateway {
        val handles = mutableListOf<FakeHandle>()
        val dispatchedKeys = mutableListOf<Int>()
        val launchCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<Pair<String, String>>()

        private var activeCalls = 0
        private var pendingLaunch: Pair<String, Int>? = null

        override fun hasNotificationListenerAccess() = listenerAccess

        override fun activeControllers(): List<MediaControllerHandle> {
            activeCalls++
            val p = pendingLaunch
            if (p != null && activeCalls > p.second) {
                pendingLaunch = null
                handles.add(FakeHandle(p.first, onPlayFromSearch = launchedSessionBehavior))
            }
            return handles.toList()
        }

        override fun dispatchMediaKey(keyCode: Int) { dispatchedKeys.add(keyCode) }

        override fun openAppSearch(app: MediaAppInfo, query: String): Boolean {
            searchCalls.add(app.packageName to query)
            return searchOpens
        }

        override fun launchApp(app: MediaAppInfo): Boolean {
            launchCalls.add(app.packageName)
            pendingLaunch = app.packageName to (activeCalls + sessionAfterLaunchPolls)
            return launchWorks
        }
    }

    private fun orchestrator(
        gateway: FakeGateway,
        installed: List<Pair<String, String>> = listOf("ru.yandex.music" to "Яндекс Музыка"),
    ) = MusicPlaybackOrchestrator(
        gateway,
        MusicAppCatalog({ installed }),
        budgets = MusicPlaybackOrchestrator.Budgets(
            verifyPollMs = 50, verifyTotalMs = 500,
            coldStartPollMs = 50, coldStartTotalMs = 2_000,
        ),
    )

    // ------------------------------------------------------------------
    // playSearchQuery
    // ------------------------------------------------------------------

    @Test
    fun `active session honors playFromSearch and start is verified`() = runTest {
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Тишина", state = NowPlaying.STATE_PAUSED),
        )
        gw.handles.add(handle)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("active_session", out.strategy)
        assertEquals("Bohemian Rhapsody", handle.lastSearched)
        assertFalse(out.isError)
        assertTrue(out.detail.contains("Включил"))
        assertEquals("Queen", out.nowPlaying?.artist)
    }

    @Test
    fun `already playing different track and title changes - verified`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(
                    title = "Старая песня", state = NowPlaying.STATE_PLAYING,
                    positionMs = 120_000,
                ),
                onPlayFromSearch = { h, q ->
                    // Player switches track mid-position: state stays playing,
                    // only the title changes.
                    h.np = h.np.copy(title = q, positionMs = 120_000)
                },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Новая песня", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("active_session", out.strategy)
    }

    @Test
    fun `player ignores playFromSearch everywhere - falls back to deep link`() = runTest {
        val gw = FakeGateway(launchedSessionBehavior = { _, _ -> /* ignore */ })
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(
                    title = "Старая песня", state = NowPlaying.STATE_PLAYING,
                    positionMs = 60_000,
                ),
                onPlayFromSearch = { _, _ -> /* ignore: old track keeps playing */ },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        // Not reported as playing — the honest outcome.
        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertEquals("deep_link", out.strategy)
        assertFalse(out.isError)
        assertEquals(listOf("ru.yandex.music" to "Bohemian Rhapsody"), gw.searchCalls)
    }

    @Test
    fun `no session - cold start then playFromSearch verified`() = runTest {
        val gw = FakeGateway()

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("cold_start", out.strategy)
        assertEquals(listOf("ru.yandex.music"), gw.launchCalls)
    }

    @Test
    fun `cold start but session never appears - deep link`() = runTest {
        val gw = FakeGateway(sessionAfterLaunchPolls = 10_000)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertEquals(listOf("ru.yandex.music"), gw.launchCalls)
    }

    @Test
    fun `deep link also fails - launch only`() = runTest {
        val gw = FakeGateway(sessionAfterLaunchPolls = 10_000, searchOpens = false)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.APP_OPENED, out.status)
        assertEquals(1, gw.launchCalls.size)
    }

    @Test
    fun `no notification listener access - deep link plus instruction`() = runTest {
        val gw = FakeGateway(listenerAccess = false)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertEquals("deep_link_no_access", out.strategy)
        assertTrue(out.detail.contains("уведомлен"))
    }

    @Test
    fun `no access and no deep link - instructive error`() = runTest {
        val gw = FakeGateway(listenerAccess = false, searchOpens = false)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.isError)
        assertTrue(out.detail.contains("уведомлен"))
    }

    @Test
    fun `no music app installed - instructive error`() = runTest {
        val gw = FakeGateway()
        val out = orchestrator(gw, installed = listOf("com.android.chrome" to "Chrome"))
            .playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.isError)
        assertTrue(out.detail.contains("музыкальное приложение"))
    }

    @Test
    fun `blank query rejected`() = runTest {
        val gw = FakeGateway()
        val out = orchestrator(gw).playSearchQuery("   ", null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.isError)
    }

    @Test
    fun `query whitespace collapsed and capped`() = runTest {
        val gw = FakeGateway()
        orchestrator(gw).playSearchQuery("  Кино   Группа крови  ", null)

        assertEquals("Кино Группа крови", gw.handles.first().lastSearched)
    }

    // ------------------------------------------------------------------
    // control
    // ------------------------------------------------------------------

    @Test
    fun `pause goes through the live session`() = runTest {
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "X", state = NowPlaying.STATE_PLAYING),
        )
        gw.handles.add(handle)

        val out = orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PAUSE, null)

        assertEquals(1, handle.pauseCalls)
        assertTrue(gw.dispatchedKeys.isEmpty())
        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
    }

    @Test
    fun `pause with no session falls back to media key`() = runTest {
        val gw = FakeGateway()

        orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PAUSE, null)

        assertEquals(listOf(MediaKey.PAUSE), gw.dispatchedKeys)
    }

    @Test
    fun `play with nothing ever played launches the player`() = runTest {
        val gw = FakeGateway()

        val out = orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PLAY, null)

        assertEquals(MusicPlaybackOrchestrator.Status.APP_OPENED, out.status)
        assertEquals(listOf("ru.yandex.music"), gw.launchCalls)
        assertEquals(listOf(MediaKey.PLAY), gw.dispatchedKeys)
    }

    // ------------------------------------------------------------------
    // nowPlaying
    // ------------------------------------------------------------------

    @Test
    fun `nowPlaying reports title and artist`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(
                    title = "Bohemian Rhapsody", artist = "Queen",
                    state = NowPlaying.STATE_PLAYING,
                ),
            ),
        )

        val out = orchestrator(gw).nowPlaying(null)

        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        assertTrue(out.detail.contains("Bohemian Rhapsody — Queen"))
        assertTrue(out.nowPlaying?.isPlaying == true)
    }

    @Test
    fun `nowPlaying with empty device - error`() = runTest {
        val gw = FakeGateway()
        val out = orchestrator(gw).nowPlaying(null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.isError)
    }

    // ------------------------------------------------------------------
    // MusicAppCatalog
    // ------------------------------------------------------------------

    @Test
    fun `catalog prefers yandex music without hint`() {
        val catalog = MusicAppCatalog {
            listOf(
                "com.zvooq.openplay" to "Звук",
                "ru.yandex.music" to "Яндекс Музыка",
            )
        }
        assertEquals("ru.yandex.music", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `catalog brand hint picks that brand`() {
        val catalog = MusicAppCatalog {
            listOf(
                "ru.yandex.music" to "Яндекс Музыка",
                "com.zvooq.openplay" to "Звук",
            )
        }
        assertEquals("com.zvooq.openplay", catalog.resolve("звук")?.packageName)
        assertEquals("com.zvooq.openplay", catalog.resolve("  Zvuk ")?.packageName)
        assertEquals("ru.yandex.music", catalog.resolve("в яндекс музыке")?.packageName)
    }

    @Test
    fun `catalog hint may match a label`() {
        val catalog = MusicAppCatalog {
            listOf("some.player" to "VK Музыка")
        }
        assertEquals("some.player", catalog.resolve("vk")?.packageName)
    }

    @Test
    fun `catalog falls back to label keywords`() {
        val catalog = MusicAppCatalog { listOf("x.player" to "Моя Музыка") }
        assertEquals("x.player", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `catalog finds nothing on a music-less device`() {
        val catalog = MusicAppCatalog { listOf("com.android.chrome" to "Chrome") }
        assertNull(catalog.resolve(null))
        assertNull(catalog.resolve("спам"))
    }

    // ------------------------------------------------------------------
    // MusicTools JSON shape
    // ------------------------------------------------------------------

    @Test
    fun `playMusic tool emits LLM-friendly JSON`() = runTest {
        val gw = FakeGateway()
        val tools = MusicTools(orchestrator(gw))
        val json = tools.all().first { it.name == "playMusic" }
            .execute("""{"query":"Bohemian Rhapsody"}""")

        assertTrue(json.contains("\"status\":\"playing\""))
        assertTrue(json.contains("\"playing\":true"))
        assertTrue(json.contains("Bohemian Rhapsody"))
        assertFalse(json.contains("\"error\""))
    }

    @Test
    fun `controlPlayback tool maps Russian actions`() = runTest {
        val gw = FakeGateway()
        val tools = MusicTools(orchestrator(gw))
        val json = tools.all().first { it.name == "controlPlayback" }
            .execute("""{"action":"дальше"}""")

        assertTrue(json.contains("\"status\":\"dispatched\""))
        assertEquals(listOf(MediaKey.NEXT), gw.dispatchedKeys)
    }

    @Test
    fun `controlPlayback tool rejects unknown action`() = runTest {
        val tools = MusicTools(orchestrator(FakeGateway()))
        val json = tools.all().first { it.name == "controlPlayback" }
            .execute("""{"action":"louder"}""")
        assertTrue(json.contains("error"))
    }

    // ------------------------------------------------------------------
    // ToolRegistry timeout override (playMusic needs ~30 s)
    // ------------------------------------------------------------------

    @Test
    fun `per-tool timeout override beats the registry default`() = runTest {
        val slow = object : com.jarvis.assistant.tools.ToolContract {
            override val name = "slow"
            override val description = ""
            override val parametersJson = "{}"
            override val timeoutMs: Long? = 60_000
            override suspend fun execute(arguments: String): String {
                kotlinx.coroutines.delay(1_000)
                return """{"status":"late"}"""
            }
        }
        val registry = com.jarvis.assistant.tools.ToolRegistry(listOf(slow), perToolTimeoutMs = 100)
        val result = registry.execute(com.jarvis.assistant.model.FunctionCall("slow", "{}"))
        assertFalse(result.isError)
        assertTrue(result.result.contains("late"))
    }
}
