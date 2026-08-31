package com.jarvis.assistant

import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.media.SearchCommand
import com.jarvis.assistant.tools.MusicTools
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 2: the rich transport — per-action capability gating with honest
 * Russian refusals, the API-29 speed guard (plan risk R7), seek/restart
 * math, heart-rating gating, repeat/shuffle wire modes, the media-key
 * fallback boundary (a media key cannot seek/like/repeat), and the
 * controlPlayback tool's argument mapping.
 */
class TransportToolsTest {

    // ------------------------------------------------------------------
    // Fake: a session whose capability mask and dispatches are inspectable
    // ------------------------------------------------------------------

    private class TransportHandle(
        override val packageName: String = "ru.yandex.music",
        var caps: MediaCapabilities = MediaCapabilities.UNKNOWN,
        var np: NowPlaying = NowPlaying(
            title = "Группа крови", artist = "Кино", album = "Группа крови",
            state = NowPlaying.STATE_PLAYING, positionMs = 90_000, durationMs = 280_000,
            queueIndex = 2, queueSize = 12,
        ),
    ) : MediaControllerHandle {
        var seekTarget: Long? = null
        var liked = false
        var repeatMode: Int? = null
        var shuffleEnabled: Boolean? = null
        var speed: Float? = null
        var queueItemId: Long? = null
        var seekFails = false

        override fun snapshot(): NowPlaying = np
        override fun capabilities(): MediaCapabilities = caps
        override fun playFromSearch(query: String): Boolean = true
        override fun play(): Boolean = true
        override fun pause(): Boolean = true
        override fun skipToNext(): Boolean = true
        override fun skipToPrevious(): Boolean = true
        override fun stop(): Boolean = true

        override fun seekTo(positionMs: Long): Boolean {
            if (seekFails) return false // remote died mid-call
            seekTarget = positionMs
            return true
        }
        override fun skipToQueueItem(queueId: Long): Boolean { queueItemId = queueId; return true }
        override fun like(): Boolean { liked = true; return true }
        override fun setRepeatMode(mode: Int): Boolean { repeatMode = mode; return true }
        override fun setShuffleMode(enabled: Boolean): Boolean { shuffleEnabled = enabled; return true }
        override fun setPlaybackSpeed(speed: Float): Boolean { this.speed = speed; return true }
    }

    private class TransportGateway(
        val handle: TransportHandle,
    ) : MediaGateway {
        override fun hasNotificationListenerAccess() = true
        override fun activeControllers(): List<MediaControllerHandle> = listOf(handle)
        override fun dispatchMediaKey(keyCode: Int) = Unit
        override fun openAppSearch(app: MediaAppInfo, query: String) = false
        override fun launchApp(app: MediaAppInfo) = false
    }

    private fun orchestrator(
        handle: TransportHandle,
        apiLevel: Int = 30,
    ) = MusicPlaybackOrchestrator(
        TransportGateway(handle),
        MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
        budgets = MusicPlaybackOrchestrator.Budgets(
            verifyPollMs = 50, verifyTotalMs = 300,
            coldStartPollMs = 50, coldStartTotalMs = 300,
        ),
        deviceApiLevel = apiLevel,
    )

    private fun fullMask(ratingType: Int = MediaCapabilities.RATING_HEART) =
        MediaCapabilities.fromActionMask(
            MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PAUSE or
                MediaCapabilities.ACTION_SEEK_TO or MediaCapabilities.ACTION_SET_RATING or
                MediaCapabilities.ACTION_SET_REPEAT_MODE or
                MediaCapabilities.ACTION_SET_SHUFFLE_MODE or
                MediaCapabilities.ACTION_SET_PLAYBACK_SPEED or
                MediaCapabilities.ACTION_SKIP_TO_NEXT or MediaCapabilities.ACTION_STOP,
            ratingType = ratingType,
            hasQueue = true,
        )

    // ------------------------------------------------------------------
    // TransportPolicy (pure gating table)
    // ------------------------------------------------------------------

    @Test
    fun `speed gate is exactly API 29`() {
        assertFalse(MusicPlaybackOrchestrator.TransportPolicy.speedAllowed(28))
        assertTrue(MusicPlaybackOrchestrator.TransportPolicy.speedAllowed(29))
        assertTrue(MusicPlaybackOrchestrator.TransportPolicy.speedAllowed(30))
    }

    @Test
    fun `media keys only exist for the basic six`() {
        listOf(
            MusicPlaybackOrchestrator.Action.PLAY,
            MusicPlaybackOrchestrator.Action.PAUSE,
            MusicPlaybackOrchestrator.Action.TOGGLE,
            MusicPlaybackOrchestrator.Action.NEXT,
            MusicPlaybackOrchestrator.Action.PREVIOUS,
            MusicPlaybackOrchestrator.Action.STOP,
        ).forEach {
            assertTrue(MusicPlaybackOrchestrator.TransportPolicy.mediaKeyEligible(it))
        }
        listOf(
            MusicPlaybackOrchestrator.Action.SEEK,
            MusicPlaybackOrchestrator.Action.RESTART,
            MusicPlaybackOrchestrator.Action.LIKE,
            MusicPlaybackOrchestrator.Action.REPEAT,
            MusicPlaybackOrchestrator.Action.SHUFFLE,
            MusicPlaybackOrchestrator.Action.SPEED,
        ).forEach {
            assertFalse(MusicPlaybackOrchestrator.TransportPolicy.mediaKeyEligible(it))
        }
    }

    @Test
    fun `like requires the heart rating type`() {
        assertTrue(
            MusicPlaybackOrchestrator.TransportPolicy.likeAllowed(
                MediaCapabilities.fromActionMask(MediaCapabilities.ACTION_SET_RATING, ratingType = MediaCapabilities.RATING_HEART),
            ),
        )
        assertFalse(
            MusicPlaybackOrchestrator.TransportPolicy.likeAllowed(
                MediaCapabilities.fromActionMask(MediaCapabilities.ACTION_SET_RATING, ratingType = MediaCapabilities.RATING_5_STARS),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Capability-gated dispatch with honest refusals
    // ------------------------------------------------------------------

    @Test
    fun `seek dispatches when the bit is present`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        val out = orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SEEK,
                deltaMs = 60_000,
            ),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        // current 90 s + 60 s forward
        assertEquals(150_000L, handle.seekTarget)
    }

    @Test
    fun `absolute seek targets the given position`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SEEK,
                positionMs = 30_000,
            ),
            null,
        )
        assertEquals(30_000L, handle.seekTarget)
    }

    @Test
    fun `seek never goes below zero`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SEEK,
                deltaMs = -300_000,
            ),
            null,
        )
        assertEquals(0L, handle.seekTarget)
    }

    @Test
    fun `seek without the bit is an honest refusal`() = runTest {
        val handle = TransportHandle(
            caps = MediaCapabilities.fromActionMask(
                MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PAUSE,
            ),
        )
        val out = orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.SEEK, deltaMs = 10_000),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertEquals("unsupported", out.strategy)
        assertTrue(out.detail.contains("перемотку"))
        assertEquals(null, handle.seekTarget) // never dispatched
    }

    @Test
    fun `restart seeks to zero`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.RESTART),
            null,
        )
        assertEquals(0L, handle.seekTarget)
    }

    @Test
    fun `like dispatches a heart rating when supported`() = runTest {
        val handle = TransportHandle(caps = fullMask(ratingType = MediaCapabilities.RATING_HEART))
        val out = orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.LIKE),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        assertTrue(handle.liked)
    }

    @Test
    fun `like with a different rating type is refused honestly`() = runTest {
        val handle = TransportHandle(caps = fullMask(ratingType = MediaCapabilities.RATING_5_STARS))
        val out = orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.LIKE),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.detail.contains("лайки"))
        assertFalse(handle.liked)
    }

    @Test
    fun `repeat one maps to the compat wire mode`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.REPEAT,
                repeatMode = MusicPlaybackOrchestrator.RepeatMode.ONE,
            ),
            null,
        )
        assertEquals(MediaCapabilities.REPEAT_MODE_ONE, handle.repeatMode)
    }

    @Test
    fun `repeat off and all map to their wire modes`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.REPEAT,
                repeatMode = MusicPlaybackOrchestrator.RepeatMode.OFF,
            ),
            null,
        )
        assertEquals(MediaCapabilities.REPEAT_MODE_NONE, handle.repeatMode)
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.REPEAT,
                repeatMode = MusicPlaybackOrchestrator.RepeatMode.ALL,
            ),
            null,
        )
        assertEquals(MediaCapabilities.REPEAT_MODE_ALL, handle.repeatMode)
    }

    @Test
    fun `shuffle on and off dispatch`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SHUFFLE,
                shuffle = true,
            ),
            null,
        )
        assertEquals(true, handle.shuffleEnabled)
        orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SHUFFLE,
                shuffle = false,
            ),
            null,
        )
        assertEquals(false, handle.shuffleEnabled)
    }

    @Test
    fun `speed dispatches on API 29+ and clamps to a sane range`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        val out = orchestrator(handle, apiLevel = 30).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SPEED,
                speed = 9.0f,
            ),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        assertEquals(4.0f, handle.speed)
    }

    @Test
    fun `speed on API 28 is refused before any dispatch`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        val out = orchestrator(handle, apiLevel = 28).control(
            MusicPlaybackOrchestrator.ControlSpec(
                action = MusicPlaybackOrchestrator.Action.SPEED,
                speed = 1.5f,
            ),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertEquals("api_guard", out.strategy)
        assertTrue(out.detail.contains("Android 10"))
        assertEquals(null, handle.speed)
    }

    @Test
    fun `rich action without any live session is honest - no random media key`() = runTest {
        val gw = object : MediaGateway {
            override fun hasNotificationListenerAccess() = true
            override fun activeControllers(): List<MediaControllerHandle> = emptyList()
            val keys = mutableListOf<Int>()
            override fun dispatchMediaKey(keyCode: Int) { keys.add(keyCode) }
            override fun openAppSearch(app: MediaAppInfo, query: String) = false
            override fun launchApp(app: MediaAppInfo) = false
        }
        val orch = MusicPlaybackOrchestrator(
            gw,
            MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
            budgets = MusicPlaybackOrchestrator.Budgets(
                verifyPollMs = 50, verifyTotalMs = 300,
                coldStartPollMs = 50, coldStartTotalMs = 300,
            ),
        )
        val out = orch.control(
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.SEEK, deltaMs = 30_000),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertTrue(out.detail.contains("Нет запущенного плеера"))
        assertTrue(gw.keys.isEmpty())
    }

    @Test
    fun `dispatch failure is reported honestly`() = runTest {
        val handle = TransportHandle(caps = fullMask()).apply { seekFails = true }
        val out = orchestrator(handle).control(
            MusicPlaybackOrchestrator.ControlSpec(MusicPlaybackOrchestrator.Action.SEEK, positionMs = 1_000),
            null,
        )
        assertEquals(MusicPlaybackOrchestrator.Status.ERROR, out.status)
        assertEquals("dispatch_failed", out.strategy)
    }

    // ------------------------------------------------------------------
    // controlPlayback tool mapping
    // ------------------------------------------------------------------

    private fun tools(handle: TransportHandle, apiLevel: Int = 30): MusicTools =
        MusicTools(orchestrator(handle, apiLevel))

    @Test
    fun `tool maps Russian seek with delta`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        val json = tools(handle).all().first { it.name == "controlPlayback" }
            .execute("""{"action":"seek","deltaMs":60000}""")
        assertTrue(json.contains("\"status\":\"dispatched\""))
        assertEquals(150_000L, handle.seekTarget)
    }

    @Test
    fun `tool maps Russian restart, like, repeat, shuffle, speed`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        val tool = tools(handle).all().first { it.name == "controlPlayback" }

        tool.execute("""{"action":"сначала"}""")
        assertEquals(0L, handle.seekTarget)

        tool.execute("""{"action":"лайкни"}""")
        assertTrue(handle.liked)

        tool.execute("""{"action":"repeat","mode":"one"}""")
        assertEquals(MediaCapabilities.REPEAT_MODE_ONE, handle.repeatMode)

        tool.execute("""{"action":"перемешай","shuffle":true}""")
        assertEquals(true, handle.shuffleEnabled)

        tool.execute("""{"action":"быстрее"}""")
        assertEquals(1.5f, handle.speed)
    }

    @Test
    fun `tool медленнее defaults to 0x75 speed`() = runTest {
        val handle = TransportHandle(caps = fullMask())
        tools(handle).all().first { it.name == "controlPlayback" }
            .execute("""{"action":"медленнее"}""")
        assertEquals(0.75f, handle.speed)
    }

    @Test
    fun `tool rejects an unknown action`() = runTest {
        val json = tools(TransportHandle(caps = fullMask())).all()
            .first { it.name == "controlPlayback" }
            .execute("""{"action":"louder"}""")
        assertTrue(json.contains("error"))
    }

    // ------------------------------------------------------------------
    // getNowPlaying v2
    // ------------------------------------------------------------------

    @Test
    fun `nowPlaying speaks queue placement and modes`() = runTest {
        val handle = TransportHandle(caps = fullMask()).apply {
            np = np.copy(
                repeatMode = MediaCapabilities.REPEAT_MODE_ONE,
                shuffleMode = MediaCapabilities.SHUFFLE_MODE_ALL,
                speed = 1.5f,
            )
        }
        val out = orchestrator(handle).nowPlaying(null)

        assertEquals(MusicPlaybackOrchestrator.Status.DISPATCHED, out.status)
        assertTrue(out.detail.contains("3 из 12"))
        assertTrue(out.detail.contains("повтор трека"))
        assertTrue(out.detail.contains("перемешано"))
        assertTrue(out.detail.contains("скорость 1.5x"))
    }

    @Test
    fun `getNowPlaying tool JSON carries the v2 fields`() = runTest {
        val handle = TransportHandle(caps = fullMask()).apply {
            np = np.copy(repeatMode = MediaCapabilities.REPEAT_MODE_ONE)
        }
        val json = tools(handle).all().first { it.name == "getNowPlaying" }
            .execute("""{}""")

        assertTrue(json.contains("\"album\":\"Группа крови\""))
        assertTrue(json.contains("\"positionSec\":90"))
        assertTrue(json.contains("\"queueIndex\":3"))
        assertTrue(json.contains("\"queueSize\":12"))
        assertTrue(json.contains("\"repeat\":\"one\""))
        assertNotNull(json)
    }
}
