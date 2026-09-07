package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.session.SessionEvent
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.session.SessionTransitions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------------
// Fakes (unique top-level names in this file — public fakes of
// SessionManagerTest.kt, same package, are reused where they are public;
// file-private classes of OTHER test files are never referenced).
// ---------------------------------------------------------------------------

/** LLM stream that parks the turn in THINKING until the session is cancelled. */
private class StormHangingLlm : LlmClient {
    val streamsLive = AtomicInteger(0)
    val peakStreams = AtomicInteger(0)

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
        val live = streamsLive.incrementAndGet()
        peakStreams.updateAndGet { maxOf(it, live) }
        try {
            awaitCancellation()
        } finally {
            streamsLive.decrementAndGet()
        }
    }
}

/** TTS that never finishes: proactive speech parks in SPEAKING. */
private class StormParkedTts : com.jarvis.assistant.speech.tts.TtsClient {
    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = flow {
        awaitCancellation()
    }
}

private class StormParkedPlayer : com.jarvis.assistant.speech.tts.TtsPlayer {
    override fun play(pcm: Flow<ByteArray>): kotlinx.coroutines.Deferred<Unit> =
        kotlinx.coroutines.CompletableDeferred()

    override fun flush() = Unit
    override fun release() = Unit
}

/** A controllable audio source (identical shape to PumpAudioSource). */
private class StormAudioSource : AudioSource {
    override fun start() = Unit
    override fun read(): ShortArray = ShortArray(320)
    override fun stop() = Unit
}

/** The control-surface operations that race each other in the storm. */
private enum class StormOp { CANCEL_ALL, STOP_ACTIVE_TURN, START_SESSION, SPEAK_PROACTIVELY }

/**
 * REMEDIATION_PLAN P3.2: supersede event-ordering race stress test.
 *
 * Context: terminal state-machine events used to be `scope.launch`ed from
 * multiple caller threads, so their APPLication order across a concurrent
 * supersede depended on dispatcher dispatch order; the sessionSeq guard
 * narrowed but did not close the race (check on the caller thread, apply
 * after a dispatch hop). SessionManager now routes every machine event
 * through ONE FIFO lane drained by ONE consumer, with the seq/state guards
 * evaluated AT APPLY TIME. This test hammers that fix.
 *
 * Each iteration: a real turn is started and parks inside the (hanging) LLM —
 * THINKING — then a pool of worker threads races
 * [SessionManager.cancelAll] / [SessionManager.stopActiveTurn] /
 * [SessionManager.startSession] / [SessionManager.speakProactively] in a
 * seeded random order (~100 seeded iterations overall).
 *
 * Invariants (per AGENTS.md "Turn terminal-event ownership"):
 *  1. The machine NEVER records an illegal transition: every recorded state
 *     pair must be reachable through the DOCUMENTED [SessionTransitions]
 *     table (≤4 hops). A stale/terminal event applied out of order would
 *     leave the machine in a state no documented edge path can explain —
 *     e.g. a stale Cancelled stomping a fresh session's LISTENING to IDLE is
 *     indistinguishable from a legal reset in one frame, but a "ToRecent"
 *     write like LlmDone-after-supersede firing twice would demand a 2nd
 *     LlmDone from IDLE (rejected by the machine and NOT recorded), so "no
 *     double-finish" manifests as every RECORDED pair staying legal.
 *  2. No orphaned turn: after quiesce, every turn's LLM stream has been
 *     cancelled and drained (streams counter → 0). Transiently two streams
 *     may be live while the superseded one drains — cancellation is async —
 *     so the check is the post-quiesce zero, not a concurrent ≤1 cap.
 *  3. No wedging: after quiesce (final cancelAll, bounded wait) the machine
 *     settles in exactly IDLE.
 *
 * Deterministic enough: seeded RNG for op placement and jitter; every wait
 * is a bounded `withTimeout` polling loop (AGENTS.md real-time budget style —
 * no busy thread-yield spinning in tight loops).
 */
class SessionManagerSupersedeStressTest {

    // ------------------------------------------------------------------
    private class Harness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val machine = SessionStateMachine()
        val llm = StormHangingLlm()
        val wake = FakeWakeWord()
        val asr = FakeAsrClient()
        val dao = FakeMessageDao()

        /**
         * Recorded machine states, in collection order, deduped pairwise
         * (StateFlow conflation may still collapse intermediate hops — the
         * reachability search absorbs that).
         */
        val recorded: MutableList<AssistantState> =
            Collections.synchronizedList(ArrayList<AssistantState>())

        val stateCollector = machine.state
            .onEach { s ->
                synchronized(recorded) {
                    if (recorded.isEmpty() || recorded.last() != s) recorded.add(s)
                }
            }
            .launchIn(scope)

        val config = JarvisConfig(
            maxUtteranceMs = Long.MAX_VALUE,
            ttsSentenceTimeoutMs = 5_000,
            ttsDrainTimeoutMs = 5_000,
            llmTimeoutMs = 10_000,
            llmRetryBackoffMs = 50,
        )

        val manager = SessionManager(
            audioPipeline = AudioPipeline(scope, StormAudioSource()),
            wakeWordDetector = wake,
            asrClient = asr,
            llm = llm,
            ttsClient = StormParkedTts(),
            player = StormParkedPlayer(),
            functionRouter = FakeTools(),
            conversationManager = com.jarvis.assistant.data.ConversationManager(dao, maxMessages = 20),
            stateMachine = machine,
            networkMonitor = FakeOnline(),
            config = config,
            scope = scope,
        )

        /** Starts a real turn and parks it inside the hanging LLM (THINKING). */
        fun parkTurnInThinking() {
            runBlocking {
                manager.startListening()
                wake.awaitSubscribed()
                wake.detections.emit(Detection.WakeWord)
                withTimeout(5_000) {
                    while (asr.streams.isEmpty()) delay(10)
                }
                asr.streams.last().emitFinal("приложение")
                withTimeout(5_000) {
                    while (machine.currentState() != AssistantState.THINKING) delay(10)
                }
            }
        }

        /**
         * Bounded quiesce: global resets (each guarded at apply time) until
         * the machine is IDLE — AND STAYS IDLE for a quiet period.
         *
         * Why the quiet period (found by diagnosis): the machine-event lane
         * is FIFO — a legitimate current-session [SessionEvent.ProactiveSpeechStarted]
         * (submitted by the coroutine [SessionManager.speakProactively]
         * launches, so its submission lands in queue order AFTER the
         * concurrent Cancelled submissions) can still be draining behind a
         * Cancelled that already returned the machine to IDLE. A single
         * IDLE snapshot races that backlog: the guard correctly PASSES the
         * queued event (its session id is still current), it applies →
         * SPEAKING 50 ms later. The state change is therefore not a bug —
         * it is the consumer legitimately still draining. So: on any change
         * during the quiet window, go back to the reset phase (whose bumped
         * seq invalidates and drops the queued event) and re-settle.
         */
        fun quiesce(): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5_000)
            var attempt = 0
            while (machine.currentState() != AssistantState.IDLE) {
                if (attempt >= 20 || System.nanoTime() > deadline) {
                    return false
                }
                manager.cancelAll()
                attempt++
                Thread.sleep(20)
            }
            // Settle loop: the machine must hold IDLE for QUIET_MS with no
            // recorded transition (lane drained). Any movement → re-reset.
            while (System.nanoTime() <= deadline) {
                val before = machine.currentState()
                val recordedLen = synchronized(recorded) { recorded.size }
                Thread.sleep(100)
                if (machine.currentState() != AssistantState.IDLE || synchronized(recorded) { recorded.size } != recordedLen) {
                    if (attempt >= 20) return false
                    manager.cancelAll()
                    attempt++
                } else if (before == AssistantState.IDLE) {
                    return true
                }
            }
            return attempt < 20 && machine.currentState() == AssistantState.IDLE
        }

        fun shutdown() {
            stateCollector.cancel()
            scope.cancel()
        }
    }

    // ------------------------------------------------------------------

    private val allEvents: List<SessionEvent> = listOf(
        SessionEvent.WakeWordOrBargeIn,
        SessionEvent.SpeechCaptured,
        SessionEvent.NoSpeech,
        SessionEvent.AsrFailed(),
        SessionEvent.LlmStarted,
        SessionEvent.LlmDone,
        SessionEvent.PlaybackStarted,
        SessionEvent.ErrorOccurred,
        SessionEvent.Cancelled,
        SessionEvent.FollowUpWindowOpened,
        SessionEvent.FollowUpSpeechDetected,
        SessionEvent.FollowUpWindowExpired,
        SessionEvent.ProactiveSpeechStarted,
    )

    private fun edges(from: AssistantState): Set<AssistantState> =
        allEvents.mapNotNull { e -> SessionTransitions.next(from, e)?.takeUnless { it == from } }.toSet()

    /** [from] can reach [to] through ≤[maxHops] documented edges? */
    private fun reachable(from: AssistantState, to: AssistantState, maxHops: Int = 4): Boolean {
        if (from == to) return true
        var frontier = setOf(from)
        repeat(maxHops) {
            frontier = frontier.flatMap { edges(it) }.toSet() - setOf(from)
            if (to in frontier) return true
        }
        return false
    }

    // ------------------------------------------------------------------

    @Test
    fun `interleaved control operations never corrupt the state machine`() {
        // AGENTS.md: real-time budgeted waits allowed; keep each one tight.
        val rng = Random(910_191L)
        val iterations = 100
        val ops = StormOp.entries.toList()
        repeat(iterations) { iteration ->
            val h = Harness()
            try {
                h.parkTurnInThinking()

                // Seeded storm: 2 rounds × 4 concurrent ops racing the turn.
                val roundOps = List(8) { ops[rng.nextInt(ops.size)] }
                val laneGate = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(4)
                val ready = CountDownLatch(4)
                roundOps.forEachIndexed { idx, op ->
                    pool.submit {
                        ready.countDown()
                        laneGate.await()
                        Thread.sleep(rng.nextInt(4).toLong()) // seeds interleaving
                        when (op) {
                            StormOp.CANCEL_ALL -> h.manager.cancelAll()
                            StormOp.STOP_ACTIVE_TURN -> h.manager.stopActiveTurn()
                            StormOp.START_SESSION -> h.manager.startSession()
                            StormOp.SPEAK_PROACTIVELY ->
                                h.manager.speakProactively("Напоминание $idx")
                        }
                    }
                }
                ready.await(5, TimeUnit.SECONDS)
                laneGate.countDown()
                pool.shutdown()
                assertTrue(
                    "storm did not settle in 5 s (iteration $iteration)",
                    pool.awaitTermination(5, TimeUnit.SECONDS),
                )
                pool.shutdownNow()

                // Quiesce: everything abandoned; machine must reach IDLE.
                assertTrue(
                    "no quiesce after storm (iteration $iteration)",
                    h.quiesce(),
                )
                assertEquals(
                    "not IDLE after quiesce (iteration $iteration)",
                    AssistantState.IDLE,
                    h.machine.currentState(),
                )

                // Invariant 1: every recorded pair is reachable through the
                // DOCUMENTED table (no illegal/stale write could have landed).
                val recorded: List<AssistantState> = synchronized(h.recorded) { h.recorded.toList() }
                for (i in 1 until recorded.size) {
                    assertTrue(
                        "illegal transition ${recorded[i - 1]} -> ${recorded[i]} (recorded=$recorded)",
                        reachable(recorded[i - 1], recorded[i]),
                    )
                }

                // Invariant 2: no orphaned LLM stream remains after quiesce —
                // every superseded turn's chatStream was cancelled and its
                // cleanup ran. (Transiently, two streams can be live while
                // the superseded one drains its `finally` — cancellation is
                // async — so "≤1 at any instant" is NOT a stable invariant;
                // "0 after quiesce, with no left-over live token" is.)
                val leakDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(5_000)
                while (h.llm.streamsLive.get() != 0) {
                    if (System.nanoTime() > leakDeadline) {
                        throw AssertionError(
                            "orphaned LLM stream after quiesce (iteration $iteration, live=${h.llm.streamsLive.get()})"
                        )
                    }
                    Thread.sleep(20)
                }
                assertTrue(
                    "no stream ever opened (iteration $iteration)",
                    h.llm.peakStreams.get() >= 1,
                )

                // Invariant 3 is the IDLE assertion above (no wedge).
            } catch (e: Exception) {
                h.scope.cancel()
                throw e
            } finally {
                h.shutdown()
            }
        }
    }
}
