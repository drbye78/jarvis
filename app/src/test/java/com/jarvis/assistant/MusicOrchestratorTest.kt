package com.jarvis.assistant

import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.media.MediaKey
import com.jarvis.assistant.media.SearchCommand
import com.jarvis.assistant.tools.MusicTools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        /** Decoded capabilities this fake reports (default: unknown/permissive). */
        var caps: MediaCapabilities = MediaCapabilities.UNKNOWN,
        /** What happens when the assistant sends playFromSearch. */
        var onPlayFromSearch: (FakeHandle, String) -> Unit = { h, q ->
            h.np = NowPlaying(
                title = q, artist = "Queen",
                state = NowPlaying.STATE_PLAYING, positionMs = 0,
            )
        },
    ) : MediaControllerHandle {
        var lastSearched: String? = null
        var lastCommand: SearchCommand? = null
        var playCalls = 0
        var pauseCalls = 0
        var nextCalls = 0
        var prevCalls = 0
        var stopCalls = 0

        override fun snapshot(): NowPlaying = np
        override fun capabilities(): MediaCapabilities = caps
        override fun playFromSearch(query: String): Boolean {
            lastSearched = query
            onPlayFromSearch(this, query)
            return true
        }

        /**
         * Tier 1: records every structured dispatch. Default mirrors the
         * interface contract (degrade to the flat call), so pre-Tier-1 tests
         * observe exactly what they observed before.
         */
        override fun playFromSearchStructured(command: SearchCommand): Boolean {
            lastCommand = command
            return super.playFromSearchStructured(command)
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
        /** M2: whether our own UI is visible (BAL-exempt) — default optimistic. */
        var uiVisible: Boolean = true,
        /** Polls of activeControllers() before a launched app posts its session. */
        var sessionAfterLaunchPolls: Int = 3,
        /** playFromSearch behavior of the session a cold start creates. */
        var launchedSessionBehavior: (FakeHandle, String) -> Unit = { h, q ->
            h.np = NowPlaying(
                title = q, artist = "Queen",
                state = NowPlaying.STATE_PLAYING, positionMs = 0,
            )
        },
        /** Tier 1 (S4): whether the player ships a legacy search activity. */
        var legacySearchHandled: Boolean = false,
        /** Polls before the legacy intent produces a session; the seeded
         * now-playing of that session (null = nothing ever appears). */
        var legacySessionPolls: Int = 2,
        var legacySessionNp: NowPlaying? = NowPlaying(
            title = "Bohemian Rhapsody", artist = "Queen",
            state = NowPlaying.STATE_PLAYING, positionMs = 0,
        ),
    ) : MediaGateway {
        val handles = mutableListOf<FakeHandle>()
        val dispatchedKeys = mutableListOf<Int>()
        val launchCalls = mutableListOf<String>()
        val searchCalls = mutableListOf<Pair<String, String>>()
        val legacyCalls = mutableListOf<Pair<String, SearchCommand>>()

        private var activeCalls = 0
        private var pendingLaunch: Triple<String, Int, NowPlaying?>? = null

        override fun hasNotificationListenerAccess() = listenerAccess
        override fun isUiVisible() = uiVisible

        override fun activeControllers(): List<MediaControllerHandle> {
            activeCalls++
            val p = pendingLaunch
            if (p != null && activeCalls > p.second) {
                pendingLaunch = null
                handles.add(
                    FakeHandle(
                        p.first,
                        np = p.third ?: NowPlaying(),
                        onPlayFromSearch = launchedSessionBehavior,
                    ),
                )
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
            pendingLaunch = Triple(app.packageName, activeCalls + sessionAfterLaunchPolls, null)
            return launchWorks
        }

        override fun sendLegacySearch(app: MediaAppInfo, command: SearchCommand): Boolean {
            legacyCalls.add(app.packageName to command)
            if (!legacySearchHandled) return false
            pendingLaunch = Triple(
                app.packageName,
                activeCalls + legacySessionPolls,
                legacySessionNp,
            )
            return true
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
    // Tier 1: structured voice search
    // ------------------------------------------------------------------

    @Test
    fun `structured slots are relayed as focus and extras`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Тишина", state = NowPlaying.STATE_PAUSED),
                onPlayFromSearch = { h, q ->
                    h.np = NowPlaying(
                        title = "Группа крови", artist = "Кино",
                        state = NowPlaying.STATE_PLAYING, positionMs = 0,
                    )
                },
            ),
        )

        val out = orchestrator(gw).playSearchQuery(
            rawQuery = "Группа крови", artist = "Кино", album = null,
            playlist = null, genre = null, appHint = null,
        )

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        val cmd = gw.handles.first().lastCommand!!
        assertEquals(SearchCommand.FOCUS_TITLE, cmd.focus)
        assertEquals("Кино", cmd.extras[SearchCommand.EXTRA_ARTIST])
        assertEquals("Группа крови", cmd.extras[SearchCommand.EXTRA_TITLE])
        // Flat text reaches extras-ignoring players too.
        assertEquals("Группа крови Кино", cmd.query)
    }

    @Test
    fun `slot-only request dispatches structured and deep-links with flat slots`() = runTest {
        val gw = FakeGateway(
            launchWorks = false,
            legacySearchHandled = false,
            launchedSessionBehavior = { _, _ -> /* ignore */ },
        )
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Старая", state = NowPlaying.STATE_PLAYING, positionMs = 60_000),
                onPlayFromSearch = { _, _ -> /* never starts anything */ },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("", artist = "Кино", album = null, playlist = null, genre = null, appHint = null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertEquals(SearchCommand.FOCUS_ARTIST, gw.handles.first().lastCommand?.focus)
        // The deep link carries the slot text — no blank search screen.
        assertEquals(listOf("ru.yandex.music" to "Кино"), gw.searchCalls)
    }

    @Test
    fun `legacy intent accepted and verified - S4`() = runTest {
        val gw = FakeGateway(
            launchWorks = false, // S3 cannot even launch (BAL)
            legacySearchHandled = true,
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("legacy_intent", out.strategy)
        assertEquals(1, gw.legacyCalls.size)
        assertEquals(
            "Bohemian Rhapsody",
            gw.legacyCalls.first().second.toLegacyIntentExtras()[SearchCommand.EXTRA_QUERY],
        )
    }

    @Test
    fun `legacy intent sent but nothing plays - falls to deep link`() = runTest {
        val gw = FakeGateway(
            launchWorks = false,
            legacySearchHandled = true,
            legacySessionNp = null, // the intent opened a search screen, no session
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertEquals("deep_link", out.strategy)
    }

    @Test
    fun `legacy intent starts the wrong track - not verified, honest outcome`() = runTest {
        val gw = FakeGateway(
            launchWorks = false,
            legacySearchHandled = true,
            legacySessionNp = NowPlaying(
                title = "Совсем другая песня", artist = "Никто",
                state = NowPlaying.STATE_PLAYING, positionMs = 0,
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
    }

    @Test
    fun `player starts a DIFFERENT track after dispatch - not verified`() = runTest {
        val gw = FakeGateway()
        // The player answers playFromSearch by playing something unrelated:
        // pre-M3 verification (position near start) called this success.
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Старая", state = NowPlaying.STATE_PAUSED),
                onPlayFromSearch = { h, _ ->
                    h.np = NowPlaying(
                        title = "Совсем другая песня", artist = "Никто",
                        state = NowPlaying.STATE_PLAYING, positionMs = 0,
                    )
                },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertEquals("deep_link", out.strategy)
    }

    // ------------------------------------------------------------------
    // Playlist-only requests: state-evidence verification (the score is 0
    // by construction — a playlist name never appears in track metadata)
    // ------------------------------------------------------------------

    @Test
    fun `playlist-only request verifies via state evidence`() = runTest {
        // The player complies: the track switches when the playlist starts.
        // The OLD code scored 0 (nothing scoreable), burned the whole verify
        // budget, then re-dispatched through the cascade and opened a search
        // screen while the playlist was audibly playing.
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 30_000),
            onPlayFromSearch = { h, _ ->
                h.np = NowPlaying(title = "Новая песня", state = NowPlaying.STATE_PLAYING, positionMs = 0)
            },
        )
        gw.handles.add(handle)

        val out = orchestrator(gw).playSearchQuery("", artist = null, album = null, playlist = "Для тренировки", genre = null, appHint = null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("active_session", out.strategy)
        assertFalse(out.isError)
    }

    @Test
    fun `playlist-only request a player ignores falls through to search`() = runTest {
        // The live session ignores the dispatch (same track, same position);
        // the cold-started fake also ignores it; no legacy activity ships —
        // the honest end state is the deep-link search screen, NOT a
        // fabricated PLAYING from the active session.
        val gw = FakeGateway(
            launchedSessionBehavior = { h, _ -> h.np = h.np }, // ignore
            legacySearchHandled = false,
            searchOpens = true,
        )
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 30_000),
            onPlayFromSearch = { _, _ -> }, // ignore
        )
        gw.handles.add(handle)

        val out = orchestrator(gw).playSearchQuery("", artist = null, album = null, playlist = "Для тренировки", genre = null, appHint = null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertNotEquals("active_session", out.strategy)
    }

    @Test
    fun `empty query with no slots is rejected`() = runTest {
        val gw = FakeGateway()
        val out = orchestrator(gw).playSearchQuery("", null, null, null, null, null)

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.isError)
        assertTrue(out.detail.contains("назови"))
    }

    // ------------------------------------------------------------------
    // Tier 1: empty-query semantics in control(PLAY)
    // ------------------------------------------------------------------

    @Test
    fun `resume from stopped sends the EMPTY playFromSearch`() = runTest {
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая", state = NowPlaying.STATE_STOPPED),
            caps = MediaCapabilities.fromActionMask(
                MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PLAY_FROM_SEARCH,
            ),
        )
        gw.handles.add(handle)

        val out = orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PLAY, null)

        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        val cmd = handle.lastCommand!!
        assertEquals("", cmd.query)
        assertTrue(cmd.isUnstructured)
        assertEquals(0, handle.playCalls) // the empty search, not a blind play()
    }

    @Test
    fun `resume from stopped without the bit falls back to play`() = runTest {
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая", state = NowPlaying.STATE_STOPPED),
            caps = MediaCapabilities.fromActionMask(MediaCapabilities.ACTION_PLAY),
        )
        gw.handles.add(handle)

        orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PLAY, null)

        assertNull(handle.lastCommand)
        assertEquals(1, handle.playCalls)
    }

    @Test
    fun `resume from paused is a plain play - no search dispatch`() = runTest {
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая", state = NowPlaying.STATE_PAUSED),
            caps = MediaCapabilities.fromActionMask(
                MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PLAY_FROM_SEARCH,
            ),
        )
        gw.handles.add(handle)

        orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PLAY, null)

        assertNull(handle.lastCommand)
        assertEquals(1, handle.playCalls)
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
        val catalog = MusicAppCatalog(installed = {
            listOf(
                "com.zvooq.openplay" to "Звук",
                "ru.yandex.music" to "Яндекс Музыка",
            )
        })
        assertEquals("ru.yandex.music", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `catalog brand hint picks that brand`() {
        val catalog = MusicAppCatalog(installed = {
            listOf(
                "ru.yandex.music" to "Яндекс Музыка",
                "com.zvooq.openplay" to "Звук",
            )
        })
        assertEquals("com.zvooq.openplay", catalog.resolve("звук")?.packageName)
        assertEquals("com.zvooq.openplay", catalog.resolve("  Zvuk ")?.packageName)
        assertEquals("ru.yandex.music", catalog.resolve("в яндекс музыке")?.packageName)
    }

    @Test
    fun `catalog hint may match a label`() {
        val catalog = MusicAppCatalog(installed = { listOf("some.player" to "VK Музыка") })
        assertEquals("some.player", catalog.resolve("vk")?.packageName)
    }

    @Test
    fun `catalog falls back to label keywords`() {
        val catalog = MusicAppCatalog(installed = { listOf("x.player" to "Моя Музыка") })
        assertEquals("x.player", catalog.resolve(null)?.packageName)
    }

    @Test
    fun `catalog finds nothing on a music-less device`() {
        val catalog = MusicAppCatalog(installed = { listOf("com.android.chrome" to "Chrome") })
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
    // M2: BAL honesty — background launch outcomes are attempts, not facts
    // ------------------------------------------------------------------

    @Test
    fun `background deep link is phrased as an attempt`() = runTest {
        val gw = FakeGateway(uiVisible = false)
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 60_000),
                onPlayFromSearch = { _, _ -> /* ignore */ },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        // Attempted phrasing + contingency instruction, not «открыл».
        assertTrue(out.detail.contains("Пытаюсь открыть поиск"))
        assertTrue(out.detail.contains("вручную"))
        assertFalse(out.detail.contains("Открыл поиск"))
    }

    @Test
    fun `foreground deep link keeps the confident phrasing`() = runTest {
        val gw = FakeGateway(uiVisible = true)
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 60_000),
                onPlayFromSearch = { _, _ -> /* ignore */ },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertTrue(out.detail.contains("Открыл поиск"))
    }

    @Test
    fun `no-access branch no longer claims the search was opened`() = runTest {
        val gw = FakeGateway(listenerAccess = false, uiVisible = false)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        // The old text asserted «Пока открыл поиск» even though a background
        // launch is typically silently blocked on Android 10+.
        assertFalse(out.detail.contains("Пока открыл"))
        assertTrue(out.detail.contains("Пытаюсь открыть"))
        assertTrue(out.detail.contains("уведомлен"))
    }

    @Test
    fun `background launch-only outcome is an attempt`() = runTest {
        val gw = FakeGateway(
            sessionAfterLaunchPolls = 10_000,
            searchOpens = false,
            uiVisible = false,
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.APP_OPENED, out.status)
        assertTrue(out.detail.contains("пробую открыть"))
        assertFalse(out.detail.contains("открыл "))
    }

    // ------------------------------------------------------------------
    // M3 interim: verification without the blind near-start rule
    // ------------------------------------------------------------------

    @Test
    fun `old track playing near its start is NOT mistaken for a fresh start`() = runTest {
        val gw = FakeGateway()
        // The player ignores playFromSearch; the OLD track happens to be just
        // a few seconds in — the deleted rule ("playing && position < 10s")
        // used to report this confident false PLAYING.
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 3_000),
                onPlayFromSearch = { h, _ ->
                    // position keeps growing, title/state unchanged
                    h.np = h.np.copy(positionMs = 3_500)
                },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        // Falls through to the honest deep-link outcome instead of lying.
        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
    }

    @Test
    fun `position reset with same title counts as a verified start`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Bohemian Rhapsody", state = NowPlaying.STATE_PLAYING, positionMs = 120_000),
                onPlayFromSearch = { h, _ ->
                    // Re-searching the SAME song restarts it: no title change,
                    // no state change — only the position resets.
                    h.np = h.np.copy(positionMs = 0)
                },
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("active_session", out.strategy)
    }

    // ------------------------------------------------------------------
    // M4: transport selection — playing session first, named-app miss honest
    // ------------------------------------------------------------------

    @Test
    fun `transport without hint prefers the playing session`() = runTest {
        val gw = FakeGateway()
        val paused = FakeHandle("com.other.player", np = NowPlaying(title = "A", state = NowPlaying.STATE_PAUSED))
        val playing = FakeHandle("ru.yandex.music", np = NowPlaying(title = "B", state = NowPlaying.STATE_PLAYING))
        gw.handles.add(paused)
        gw.handles.add(playing) // active order: paused first, playing second

        orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PAUSE, null)

        assertEquals(1, playing.pauseCalls)
        assertEquals(0, paused.pauseCalls)
    }

    @Test
    fun `named app with no session answers honestly instead of pausing another player`() = runTest {
        val gw = FakeGateway()
        // Another player is active and playing; the user named Yandex Music.
        gw.handles.add(
            FakeHandle("com.other.player", np = NowPlaying(title = "X", state = NowPlaying.STATE_PLAYING)),
        )

        val out = orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PAUSE, "яндекс")

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertEquals("named_app_miss", out.strategy)
        assertTrue(out.isError)
        assertTrue(out.detail.contains("ничего не играет"))
        // The other player was NOT touched.
        assertEquals(0, gw.handles.first().pauseCalls)
        assertTrue(gw.dispatchedKeys.isEmpty())
    }

    @Test
    fun `named app with a session still wins over a playing stranger`() = runTest {
        val gw = FakeGateway()
        val stranger = FakeHandle("com.other.player", np = NowPlaying(title = "X", state = NowPlaying.STATE_PLAYING))
        val named = FakeHandle("ru.yandex.music", np = NowPlaying(title = "Y", state = NowPlaying.STATE_PAUSED))
        gw.handles.add(stranger)
        gw.handles.add(named)

        orchestrator(gw).control(MusicPlaybackOrchestrator.Action.PLAY, "яндекс")

        assertEquals(1, named.playCalls)
        assertEquals(0, stranger.playCalls)
    }

    @Test
    fun `nowPlaying named-app miss reports the named player, not a stranger`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle("com.other.player", np = NowPlaying(title = "X", state = NowPlaying.STATE_PLAYING)),
        )

        val out = orchestrator(gw).nowPlaying("яндекс")

        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.detail.contains("Яндекс Музыка"))
        assertFalse(out.detail.contains("X"))
    }

    @Test
    fun `nowPlaying without hint prefers the playing session`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle("com.paused.player", np = NowPlaying(title = "Пауза", state = NowPlaying.STATE_PAUSED)),
        )
        gw.handles.add(
            FakeHandle("ru.yandex.music", np = NowPlaying(title = "Живая", state = NowPlaying.STATE_PLAYING)),
        )

        val out = orchestrator(gw).nowPlaying(null)

        assertTrue(out.detail.contains("Живая"))
        assertFalse(out.detail.contains("Пауза"))
    }

    // ------------------------------------------------------------------
    // Tier 0: capability-gated dispatch
    // ------------------------------------------------------------------

    @Test
    fun `live session without playFromSearch bit is skipped without dispatch`() = runTest {
        val gw = FakeGateway()
        val handle = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 60_000),
            caps = MediaCapabilities.fromActionMask(
                MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PAUSE,
            ),
            onPlayFromSearch = { _, _ -> /* would be ignored anyway */ },
        )
        gw.handles.add(handle)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        // The dispatch never happened — the player cannot honor it — and no
        // verify budget was burned waiting for evidence that can never come
        // (virtual clock barely advanced past zero).
        assertNull(handle.lastSearched)
        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertTrue(
            "gate must skip before the verify budget, elapsed=${testScheduler.currentTime}",
            testScheduler.currentTime < 100,
        )
    }

    @Test
    fun `cold-start session without playFromSearch bit skips straight to deep link`() = runTest {
        val gw = FakeGateway()
        // A pre-seeded session of the target app with a play/pause-only mask:
        // launching the app again cannot add capabilities to the same session.
        val cold = FakeHandle(
            "ru.yandex.music",
            np = NowPlaying(title = "Старая песня", state = NowPlaying.STATE_PLAYING, positionMs = 60_000),
            caps = MediaCapabilities.fromActionMask(
                MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PAUSE,
            ),
            onPlayFromSearch = { _, _ -> /* would be ignored anyway */ },
        )
        gw.handles.add(cold)

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        // Live branch: caps lack playFromSearch -> skip. Cold branch: the same
        // session is found -> skip. Deep link is the honest outcome.
        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertNull(cold.lastSearched)
    }

    @Test
    fun `unknown capabilities stay permissive`() = runTest {
        val gw = FakeGateway()
        gw.handles.add(
            FakeHandle(
                "ru.yandex.music",
                np = NowPlaying(title = "Тишина", state = NowPlaying.STATE_PAUSED),
                caps = MediaCapabilities.UNKNOWN, // no PlaybackState published yet
            ),
        )

        val out = orchestrator(gw).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(MusicPlaybackOrchestrator.Status.PLAYING, out.status)
        assertEquals("active_session", out.strategy)
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
        val result = registry.executeResult(com.jarvis.assistant.model.FunctionCall("slow", "{}"))
        assertFalse(result.isError)
        assertTrue(result.content.contains("late"))
    }
}
