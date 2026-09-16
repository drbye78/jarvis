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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
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
        /** Gateway actions attempted — proves a cascade actually ran. */
        var actions = 0
        override fun hasNotificationListenerAccess() = true
        override fun activeControllers(): List<MediaControllerHandle> = emptyList()
        override fun dispatchMediaKey(keyCode: Int) { actions++ }
        override fun openAppSearch(app: MediaAppInfo, query: String): Boolean {
            actions++
            return false
        }
        override fun launchApp(app: MediaAppInfo): Boolean { actions++; return false }
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
            verifyPollMs = 50,
            verifyTotalMs = 300,
            coldStartPollMs = 50,
            coldStartTotalMs = 300,
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
            override fun requestDuckFocus(): Boolean {
                requests++
                return true
            }
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
            tts,
            FakePlayer(),
            { "Mila" },
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
            tts,
            FakePlayer(),
            { "Mila" },
            focus,
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
        val tts = FakeTts()
        val player = FakePlayer().apply { playThrows = true }
        val adapter = object : AudioFocusAdapter {
            var abandons = 0
            override fun requestDuckFocus() = true
            override fun abandonFocus() { abandons++ }
        }
        val focus = AssistantAudioFocus(adapter)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val fb = TtsSpeechFeedback(
            scope,
            tts,
            player,
            { "Mila" },
            focus,
        )

        fb.onCascadeStarted(predictedLong = true)

        // The contract is THREE observables, not "the test did not crash":
        // (a) the phrase was actually synthesized and play() really threw —
        //     FakePlayer records only on success, so an EMPTY played list is
        //     the proof the throw path ran (a silent no-op would pass (c)
        //     without ever exercising it);
        // (b) the duck that was taken for the phrase was released by the
        //     catch (focus.onTtsFlushed) — an un-balanced duck would leave
        //     other audio permanently attenuated;
        // (c) the failure stayed inside the child: the scope is Unconfined, so
        //     the launch ran to completion INSIDE onCascadeStarted() on this
        //     very thread — an exception that escaped the catch would have
        //     propagated into the test before any assertion below, and the
        //     scope would be left cancelled.
        assertEquals(
            "play() must have been reached and thrown (a skipped play means the catch was never exercised)",
            1,
            tts.spoken.size,
        )
        assertEquals("the throwing player must not have recorded a play", 0, player.played.size)
        assertEquals("the duck must be released on the failure path", 1, adapter.abandons)
        assertEquals(AssistantFocusState.IDLE, focus.state)
        assertTrue(
            "the scope must survive a swallowed player exception (an escaped one tears it down)",
            scope.coroutineContext.job.isActive,
        )
        scope.cancel()
    }

    @Test
    fun `cancellation propagates after focus cleanup - A8 pattern`() = runBlocking {
        // A8 regression: the CancellationException used to be SWALLOWED after
        // the focus cleanup, breaking structured concurrency (the child kept
        // running after scope.cancel() as if nothing happened). The cleanup
        // must still run, but the child must end CANCELLED.
        val flushed = CompletableDeferred<Unit>()
        val adapter = object : AudioFocusAdapter {
            override fun requestDuckFocus() = true
            override fun abandonFocus() { flushed.complete(Unit) }
        }
        val focus = AssistantAudioFocus(adapter)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val player = object : TtsPlayer {
            val parked = CompletableDeferred<Unit>()
            override fun play(pcm: Flow<ByteArray>): CompletableDeferred<Unit> = parked
            override fun flush() = Unit
            override fun release() = Unit
        }
        val fb = TtsSpeechFeedback(
            scope,
            FakeTts(),
            player,
            { "Mila" },
            focus,
        )

        // Unconfined: the speak child runs inline up to the parked await.
        fb.onCascadeStarted(predictedLong = true)

        val parentJob = scope.coroutineContext.job
        val child = withTimeout(2_000) {
            var found: kotlinx.coroutines.Job? = null
            while (found == null) {
                kotlinx.coroutines.delay(1)
                found = parentJob.children.firstOrNull()
            }
            found
        }

        // Barge-in: the awaited sentence deferred settles with cancellation —
        // the CE must propagate through speak()'s catch (after cleanup), so
        // the child ends CANCELLED (a swallowed CE would end it NORMALLY).
        player.parked.completeExceptionally(kotlinx.coroutines.CancellationException("flushed"))
        child.join()
        assertTrue(
            "the speak child must end CANCELLED — swallowing the CancellationException breaks structured concurrency",
            child.isCancelled,
        )
        // Cleanup ran BEFORE the rethrow.
        assertTrue("focus cleanup must still run on the cancellation path", flushed.isCompleted)
        assertEquals(AssistantFocusState.IDLE, focus.state)
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // None implementation (default silence)
    // ------------------------------------------------------------------

    @Test
    fun `None implementation is silent and safe`() = runTest {
        // The contract of a no-op is observable in two directions, and both
        // are asserted here instead of "we got here":
        //  1. it never throws, whatever it is handed (labels come from tool
        //     payloads the orchestrator cannot vet);
        //  2. it is a DROP-IN the real cascade tolerates end-to-end — a
        //     feedback that silently swallowed the cascade, or that the
        //     orchestrator could not call, would fail this.
        val outcome = runCatching {
            SpeechFeedback.None.onCascadeStarted(predictedLong = true)
            SpeechFeedback.None.onCascadeStarted(predictedLong = false)
            SpeechFeedback.None.onLaunchingPlayer("Яндекс Музыка")
            // Same cascade as the recorder tests above, with None wired in.
            val gw = Gw()
            orchestrator(SpeechFeedback.None, gw).playSearchQuery("Bohemian Rhapsody", null)
            gw.actions
        }
        assertTrue(
            "SpeechFeedback.None must never throw, got: ${outcome.exceptionOrNull()}",
            outcome.isSuccess,
        )
        assertTrue(
            "the cascade must still have driven the gateway while feedback was None",
            outcome.getOrDefault(0) > 0,
        )
    }
}
