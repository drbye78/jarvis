package com.jarvis.assistant

import com.jarvis.assistant.audio.AssistantAudioFocus
import com.jarvis.assistant.audio.AssistantFocusState
import com.jarvis.assistant.audio.AudioFocusAdapter
import com.jarvis.assistant.audio.SpeechFeedback
import com.jarvis.assistant.audio.TtsSpeechFeedback
import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (M5): spoken cascade progress. The orchestrator emits
 * «Секунду…»-class feedback only when the cascade predicts a long path,
 * «Открываю плеер…» before activity launches, and the TTS-backed
 * implementation is best-effort: synthesis/playback failures and player
 * exceptions never propagate into the cascade.
 */
class SpeechFeedbackTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    private class FakeTts : TtsClient {
        val spoken = mutableListOf<String>()
        var fail = false

        override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> {
            spoken += text
            if (fail) throw IllegalStateException("gRPC down")
            return flowOf(ByteArray(16))
        }
    }

    private class FakePlayer : TtsPlayer {
        val played = mutableListOf<Int>()
        var playThrows = false

        override fun play(pcm: Flow<ByteArray>): CompletableDeferred<Unit> {
            if (playThrows) throw RuntimeException("AudioTrack died")
            played += pcm.hashCode()
            return CompletableDeferred(Unit) // drains immediately
        }

        override fun flush() = Unit
        override fun release() = Unit
    }

    private class Gw : MediaGateway {
        override fun hasNotificationListenerAccess() = true
        override fun activeControllers(): List<MediaControllerHandle> = emptyList()
        override fun dispatchMediaKey(keyCode: Int) = Unit
        override fun openAppSearch(app: MediaAppInfo, query: String) = false
        override fun launchApp(app: MediaAppInfo) = false
    }

    private fun recorder() = object : SpeechFeedback {
        val events = mutableListOf<String>()
        override fun onCascadeStarted(predictedLong: Boolean) {
            events += (if (predictedLong) "long" else "short")
        }

        override fun onLaunchingPlayer(label: String) { events += "launch:$label" }
    }

    private fun orchestrator(
        feedback: SpeechFeedback?,
        gateway: MediaGateway = Gw(),
    ) = MusicPlaybackOrchestrator(
        gateway,
        MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
        budgets = MusicPlaybackOrchestrator.Budgets(
            verifyPollMs = 50, verifyTotalMs = 300,
            coldStartPollMs = 50, coldStartTotalMs = 300,
            legacyWaitTotalMs = 300,
        ),
        feedback = feedback,
    )

    // ------------------------------------------------------------------
    // Orchestrator emission rules
    // ------------------------------------------------------------------

    @Test
    fun `cold cascade announces a long path`() = runTest {
        val fb = recorder()
        orchestrator(fb).playSearchQuery("Bohemian Rhapsody", null)

        assertEquals(listOf("long"), fb.events.filter { it == "long" || it == "short" })
        // launch feedback also fired (activity strategies ran)
        assertTrue(fb.events.any { it.startsWith("launch:") })
    }

    @Test
    fun `live session stays silent - fast path`() = runTest {
        val fb = recorder()
        val gw = object : MediaGateway {
            override fun hasNotificationListenerAccess() = true
            override fun activeControllers(): List<MediaControllerHandle> =
                listOf(object : MediaControllerHandle {
                    override val packageName = "ru.yandex.music"
                    var np: NowPlaying = NowPlaying(title = "Тишина", state = NowPlaying.STATE_PAUSED)
                    override fun snapshot() = np
                    override fun capabilities() = MediaCapabilities.UNKNOWN
                    override fun playFromSearch(query: String): Boolean {
                        np = NowPlaying(
                            title = "Bohemian Rhapsody", artist = "Queen",
                            state = NowPlaying.STATE_PLAYING,
                        )
                        return true
                    }

                    override fun play(): Boolean = true
                    override fun pause(): Boolean = true
                    override fun skipToNext(): Boolean = true
                    override fun skipToPrevious(): Boolean = true
                    override fun stop(): Boolean = true
                })

            override fun dispatchMediaKey(keyCode: Int) = Unit
            override fun openAppSearch(app: MediaAppInfo, query: String) = false
            override fun launchApp(app: MediaAppInfo) = false
        }

        orchestrator(fb, gateway = gw).playSearchQuery("Bohemian Rhapsody", null)

        // Fast path: no long-path announcement, no launch phrase. (The
        // interface still reports the SHORT prediction — the TTS impl
        // ignores it; that filtering is tested below.)
        assertTrue(fb.events.none { it == "long" })
        assertTrue(fb.events.none { it.startsWith("launch:") })
    }

    // ------------------------------------------------------------------
    // TtsSpeechFeedback implementation semantics
    // ------------------------------------------------------------------

    @Test
    fun `feedback speaks and brackets focus - and never throws`() = runTest {
        val tts = FakeTts()
        val player = FakePlayer()
        val adapter = object : AudioFocusAdapter {
            var requests = 0
            var abandons = 0
            override fun requestDuckFocus(): Boolean { requests++; return true }
            override fun abandonFocus() { abandons++ }
        }
        val focus = AssistantAudioFocus(adapter)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

        val fb = TtsSpeechFeedback(scope, tts, player, { "Mila" }, focus)
        fb.onCascadeStarted(predictedLong = true)
        fb.onLaunchingPlayer("Яндекс Музыку")

        withTimeout(2_000) {
            // Unconfined scope: the phrases ran synchronously.
            while (tts.spoken.size < 2) kotlinx.coroutines.delay(10)
        }
        assertEquals(listOf("Секунду.", "Открываю Яндекс Музыку."), tts.spoken)
        // The two phrases fire seconds apart in a real cascade (browser
        // attempts run between them), so each ducks independently:
        // request → speak → abandon, twice — never a leaked duck.
        assertEquals(2, adapter.requests)
        assertEquals(2, adapter.abandons)
        assertEquals(AssistantFocusState.IDLE, focus.state)
    }

    @Test
    fun `short path stays silent`() = runTest {
        val tts = FakeTts()
        val fb = TtsSpeechFeedback(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            tts, FakePlayer(), { "Mila" },
        )
        fb.onCascadeStarted(predictedLong = false)
        assertEquals(emptyList<String>(), tts.spoken)
    }

    @Test
    fun `synthesis failure is swallowed`() = runTest {
        val tts = FakeTts().apply { fail = true }
        val focus = AssistantAudioFocus(object : AudioFocusAdapter {
            override fun requestDuckFocus() = true
            override fun abandonFocus() = Unit
        })
        val fb = TtsSpeechFeedback(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            tts, FakePlayer(), { "Mila" }, focus,
        )

        fb.onCascadeStarted(predictedLong = true)

        // The phrase failed, the duck was released, nothing propagated.
        withTimeout(2_000) {
            while (tts.spoken.isEmpty()) kotlinx.coroutines.delay(10)
        }
        assertEquals(AssistantFocusState.IDLE, focus.state)
    }

    @Test
    fun `player exception is swallowed`() = runTest {
        val fb = TtsSpeechFeedback(
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            FakeTts(), FakePlayer().apply { playThrows = true }, { "Mila" },
        )

        fb.onCascadeStarted(predictedLong = true)

        withTimeout(2_000) {
            kotlinx.coroutines.delay(50) // let the unconfined job run
        }
        // Reached here without an unhandled exception crashing the test.
        assertTrue(true)
    }

    // ------------------------------------------------------------------
    // None implementation (default silence)
    // ------------------------------------------------------------------

    @Test
    fun `None implementation is silent and safe`() {
        SpeechFeedback.None.onCascadeStarted(predictedLong = true)
        SpeechFeedback.None.onLaunchingPlayer("X")
        // No crash, no output — the contract of a no-op.
        assertTrue(true)
    }
}
