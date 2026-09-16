package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.session.SessionEvent
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.session.SpeechPhrases
import com.jarvis.assistant.session.TurnRunner
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

// ---------------------------------------------------------------------------
// REMEDIATION_PLAN P1.6: DIRECT TurnRunner coverage (previously only indirect
// through SessionManagerTest). TurnRunner holds no state-machine of its own —
// every terminal transition is routed to the four injected callbacks — so this
// harness wires the runner to recording callbacks instead of a SessionManager.
//
// Reuses the PUBLIC fakes from SessionManagerTest.kt (FakeAsrClient,
// FakeAsrStream, ScriptedLlm, FlakyLlm, FakeTtsClient, FakePlayer,
// GatedPlayer, FakeTools, HangOnNthTools, PumpAudioSource) and FakeMessageDao
// from cognitive/extract. File-local helpers get TurnRunner-unique names
// (the file-private top-level name-clash lesson).
// ---------------------------------------------------------------------------

/** LLM that requests a tool call on EVERY pass — drives the loop into its cap. */
private class ToolCallLoopLlm : LlmClient {
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
        requests.add(request)
        emit(LlmChunk.FunctionCallDelta(0, name = "setAlarm", argsDelta = "{}"))
        emit(
            LlmChunk.FunctionCallComplete(
                ToolCall("call_${requests.size}", function = FunctionCall("setAlarm", "{}"))
            )
        )
        emit(LlmChunk.Done)
    }
}

/** LLM that fails EVERY attempt before emitting anything (zero-output rule). */
private class AlwaysFailingLlm(private val error: Exception) : LlmClient {
    val attempts = java.util.concurrent.atomic.AtomicInteger(0)

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
        attempts.incrementAndGet()
        throw error
    }
}

/** Tools whose execution always fails the turn (non-cancellation exception). */
private class ExplodingTools : FakeTools() {
    override suspend fun executeResult(call: FunctionCall): ToolResult {
        error("tool exploded")
    }
}

/** Direct-runner harness: recording callbacks instead of a SessionManager. */
private class TurnRunnerHarness(
    val llm: LlmClient,
    config: JarvisConfig = JarvisConfig(
        maxUtteranceMs = Long.MAX_VALUE,
        ttsSentenceTimeoutMs = 5_000,
        ttsDrainTimeoutMs = 5_000,
        llmTimeoutMs = 10_000,
        llmRetryBackoffMs = 10, // fast retries under test
        maxToolPasses = 5,
        llmMaxRetries = 1,
    ),
    toolsOverride: FakeTools? = null,
    playerOverride: TtsPlayer? = null,
    /**
     * Optional extra observer run INSIDE the turn coroutine on every state
     * event (after the recording) — lets tests flip harness state at exact
     * points of the turn timeline (e.g. supersede the session between
     * phases) or launch stray session-scope children for drain tests.
     */
    val onStateEventExtra: (suspend (SessionEvent) -> Unit)? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = FakeMessageDao()
    val conversation = ConversationManager(dao, maxMessages = 20)
    val pipeline = AudioPipeline(scope, PumpAudioSource())
    val asr = FakeAsrClient()
    val stateMachine = SessionStateMachine()

    val events = CopyOnWriteArrayList<SessionEvent>()

    /**
     * P1-S #1: the SESSION SEQ each event was emitted with (parallel to
     * [events]). A turn's emissions must all carry the id [runTurn] got —
     * never the manager's "current" seq at apply time.
     */
    val eventSeqs = CopyOnWriteArrayList<Int>()

    /**
     * P1-S #1 (extended): the session id stamped on each [setPartial] call,
     * parallel to [partials] — same rule, different wire (shared UI state
     * instead of the machine).
     */
    val partialSeqs = CopyOnWriteArrayList<Int>()
    val failures = CopyOnWriteArrayList<Pair<Int?, String>>()
    val finished = CopyOnWriteArrayList<Pair<Int, Boolean>>()
    val partials = CopyOnWriteArrayList<String>()

    /**
     * Mirrors SessionManager.finish's stale-session guard: when [superseded]
     * is flipped (stopActiveTurn / startSession bumped the seq), a dying
     * turn's finish() and history writes must be dropped.
     */
    @Volatile var superseded = false

    val tts = FakeTtsClient()
    val tools: FakeTools = toolsOverride ?: FakeTools()

    /** Exposed so gated-player tests can inspect enqueue gates. */
    val player: TtsPlayer = playerOverride ?: FakePlayer()

    val runner = TurnRunner(
        audioPipeline = pipeline,
        asrClient = asr,
        llm = llm,
        ttsClient = tts,
        player = player,
        functionRouter = tools,
        conversationManager = conversation,
        config = config,
        // Mirror SessionManager's wiring: events drive the machine; the two
        // terminals map to ErrorOccurred (reportFailure — THE error-turn
        // terminal) and LlmDone (finish). Rejected transitions would throw
        // inside onEvent? No — the machine logs and keeps state, so the
        // explicit state assertions below are the legality check.
        onStateEvent = { turnEvent ->
            // P1-S #1: TurnRunner now hands over a TurnEvent (event + the seq
            // of the turn that emitted it). The harness keeps recording the
            // bare event so every existing assertion is unchanged, and mirrors
            // the manager's wiring by driving the machine with the event only.
            events.add(turnEvent.event)
            eventSeqs.add(turnEvent.sessionSeq)
            stateMachine.onEvent(turnEvent.event)
            onStateEventExtra?.invoke(turnEvent.event)
        },
        reportFailure = { id, msg ->
            failures.add(id to msg)
            stateMachine.onEvent(SessionEvent.ErrorOccurred)
        },
        finish = { id, spoke ->
            if (!superseded) {
                finished.add(id to spoke)
                stateMachine.onEvent(SessionEvent.LlmDone)
            }
        },
        // P1-S #1 (extended): the partial writer is turn-id stamped too. The
        // bare texts stay recorded for the existing transcript assertions;
        // [partialSeqs] is the provenance side of the same wire.
        setPartial = { id, text ->
            partials.add(text)
            partialSeqs.add(id)
        },
        isCurrentSession = { !superseded && it == 1 },
    )

    fun shutdown() {
        pipeline.release()
        scope.cancel()
    }

    /**
     * Launches the turn exactly like SessionManager.startSession does (a
     * child of the session scope) and returns the job plus an outcome
     * capture: null = returned normally, otherwise the thrown Throwable.
     */
    fun launchTurn(): Pair<kotlinx.coroutines.Job, CompletableDeferred<Throwable?>> {
        val outcome = CompletableDeferred<Throwable?>()
        val job = scope.launch {
            try {
                with(runner) { runTurn(1) }
                outcome.complete(null)
            } catch (t: Throwable) {
                outcome.complete(t)
            }
        }
        return job to outcome
    }

    suspend fun deliverUtterance(text: String) {
        withTimeout(5_000) {
            while (asr.streams.isEmpty()) delay(20)
        }
        val stream = asr.streams.last()
        stream.awaitEvents()
        stream.emitFinal(text)
    }
}

private suspend fun awaitCond(budgetMs: Long = 10_000, cond: () -> Boolean) {
    withTimeout(budgetMs) {
        while (!cond()) delay(20)
    }
}

class TurnRunnerDirectTest {

    // ------------------------------------------------------------------
    // Tool-pass budget: the loop must stop AT the cap (maxToolPasses=5)
    // ------------------------------------------------------------------

    @Test
    fun `tool loop stops at the maxToolPasses cap and fails the turn`() = runBlocking {
        val llm = ToolCallLoopLlm()
        val h = TurnRunnerHarness(llm) // config.maxToolPasses = 5 (production default)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("поставь будильник")

            awaitCond { h.failures.isNotEmpty() }
            job.join()
            assertNull("turn must end via reportFailure, not an exception", outcome.await())

            // Passes 1..5 each consulted the LLM and executed a tool; pass 6
            // trips the cap — exactly 5 requests, never a 6th.
            assertEquals(5, llm.requests.size)
            assertEquals(5, h.tools.executed.size)
            assertEquals(
                listOf(1 to SpeechPhrases.Default.tooManyToolSteps),
                h.failures.toList(),
            )
            assertTrue("budget trip must NOT finish the turn", h.finished.isEmpty())
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Zero-output retry (llmMaxRetries=1)
    // ------------------------------------------------------------------

    @Test
    fun `zero-output transient failure is retried once and the turn completes`() = runBlocking {
        val flaky = FlakyLlm(failures = 1, error = java.io.IOException("connection reset"))
        val h = TurnRunnerHarness(flaky)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("Привет, Джарвис")

            awaitCond { h.finished.isNotEmpty() }
            job.join()
            assertNull(outcome.await())

            // First attempt failed with zero output; the single retry (budget
            // llmMaxRetries=1) succeeded — two attempts total.
            assertEquals(2, flaky.attempts.get())
            assertTrue(h.failures.isEmpty())
            assertEquals(listOf(1 to true), h.finished.toList())
            assertTrue(
                h.dao.rows.values.any { it.role == "assistant" && it.content.contains("Готово") },
            )
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Error paths: LLM failure → reportFailure; tool failure → reportFailure
    // ------------------------------------------------------------------

    @Test
    fun `LLM failure after the retry budget reports failure instead of finishing`() = runBlocking {
        val llm = AlwaysFailingLlm(java.io.IOException("dead upstream"))
        val h = TurnRunnerHarness(llm)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("Привет")

            awaitCond { h.failures.isNotEmpty() }
            job.join()
            assertNull(outcome.await())

            // llmMaxRetries=1 → exactly two attempts, then the funnel.
            assertEquals(2, llm.attempts.get())
            assertEquals(
                listOf(1 to SpeechPhrases.Default.llmFailed),
                h.failures.toList(),
            )
            assertTrue(h.finished.isEmpty())
            // Nothing persisted: no user-less assistant rows, no dangling text.
            assertEquals(listOf("user"), h.dao.rows.values.map { it.role })
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `tool failure funnels through reportFailure with the generic error`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf(
                    LlmChunk.FunctionCallDelta(0, name = "setAlarm", argsDelta = "{}"),
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_1", function = FunctionCall("setAlarm", "{}"))
                    ),
                    LlmChunk.Done,
                ),
            )
        )
        val h = TurnRunnerHarness(llm, toolsOverride = ExplodingTools())
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("поставь будильник")

            awaitCond { h.failures.isNotEmpty() }
            job.join()
            assertNull(outcome.await())

            assertEquals(
                listOf(1 to SpeechPhrases.Default.genericError),
                h.failures.toList(),
            )
            assertTrue(h.finished.isEmpty())
            // The half-executed tool pass is NOT persisted (no dangling pair).
            assertEquals(0, h.dao.rows.values.count { it.role == "tool" })
            assertEquals(
                0,
                h.dao.rows.values.count { it.role == "assistant" && it.toolCallsJson != null },
            )
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Mid-turn stop (stopActiveTurn semantics reach the runner as a
    // cancellation of the session job): the runner finishes the turn with the
    // CURRENT spoke flag before rethrowing — and a superseded seq drops it.
    // ------------------------------------------------------------------

    @Test
    fun `mid-turn cancellation during THINKING finishes the turn then propagates`() = runBlocking {
        // Hanging LLM parks the turn in THINKING.
        val llm = object : LlmClient {
            override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val h = TurnRunnerHarness(llm)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("тест")
            awaitCond { h.events.contains(SessionEvent.LlmStarted) }

            job.cancel() // what stopActiveTurn does to the session job
            assertTrue(
                "CancellationException must propagate",
                outcome.await() is kotlinx.coroutines.CancellationException
            )

            // The runner ran its cancellation cleanup BEFORE rethrowing.
            assertEquals(listOf(1 to false), h.finished.toList())
            assertTrue(h.failures.isEmpty())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `mid-turn cancellation during SPEAKING finishes with spoke=true`() = runBlocking {
        val player = GatedPlayer() // sentence parks in playback
        val llm = ScriptedLlm(
            mutableListOf(listOf(LlmChunk.Text("Длинный ответ."), LlmChunk.Done))
        )
        val h = TurnRunnerHarness(llm, playerOverride = player)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("тест")
            awaitCond { h.events.contains(SessionEvent.PlaybackStarted) }
            // Wait until the sentence actually reached the player (the gate
            // is created inside play()): per the audit fix the spoke flag is
            // earned by audio reaching playback, so the stop must land AFTER
            // that point for eligibility to survive the cancellation.
            awaitCond { player.gates.isNotEmpty() }
            assertEquals(1, player.gates.size)

            job.cancel()
            assertTrue(outcome.await() is kotlinx.coroutines.CancellationException)

            // spoke was already true when the stop landed — follow-up-window
            // eligibility information must survive the cancellation.
            assertEquals(listOf(1 to true), h.finished.toList())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `cancellation mid-tool-pass persists the COMPLETED subset`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf( // pass 1: TWO tool calls; the second never finishes
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_1", function = FunctionCall("setAlarm", "{}"))
                    ),
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_2", function = FunctionCall("getWeather", "{}"))
                    ),
                    LlmChunk.Done,
                ),
            )
        )
        val tools = HangOnNthTools(hangFrom = 2)
        val h = TurnRunnerHarness(llm, toolsOverride = tools)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("тест")
            awaitCond { tools.executed.size == 2 }

            job.cancel()
            assertTrue(outcome.await() is kotlinx.coroutines.CancellationException)

            // Atomic subset persistence (C2): the assistant row carries ONLY
            // call_1, paired with its tool result; call_2 is absent entirely.
            val assistant = h.dao.rows.values.single { it.role == "assistant" && it.toolCallsJson != null }
            assertTrue(assistant.toolCallsJson!!.contains("call_1"))
            assertFalse(assistant.toolCallsJson!!.contains("call_2"))
            val toolRows = h.dao.rows.values.filter { it.role == "tool" }
            assertEquals(1, toolRows.size)
            assertEquals("call_1", toolRows[0].toolCallId)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `superseded turn persists nothing on cancellation`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf(
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_1", function = FunctionCall("setAlarm", "{}"))
                    ),
                    LlmChunk.Done,
                ),
            )
        )
        val tools = HangOnNthTools(hangFrom = 1)
        val h = TurnRunnerHarness(llm, toolsOverride = tools)
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("тест")
            awaitCond { tools.executed.size == 1 }

            // Barge-in / stop: the seq bump happens BEFORE the cancel — the
            // dying turn is already stale when its cleanup runs.
            h.superseded = true
            job.cancel()
            assertTrue(outcome.await() is kotlinx.coroutines.CancellationException)

            delay(200) // settle: any (incorrect) write would land here
            assertEquals(
                "superseded turn must not poison history",
                listOf("user"),
                h.dao.rows.values.map { it.role },
            )
            assertTrue("superseded finish must be dropped", h.finished.isEmpty())
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Sentence children are launched on the SESSION scope, not the
    // withTimeout(llm) scope (TurnRunner.kt comment at the sentence launch):
    // if they were children of the timeout job, the timeout's structured
    // completion would wait for gated playback, the LLM timeout (400 ms)
    // would fire mid-playback and the turn would die with llmTimeout.
    // ------------------------------------------------------------------

    @Test
    fun `sentence playback survives past the LLM timeout - children are not timeout children`() =
        runBlocking {
            val llm = ScriptedLlm(
                mutableListOf(listOf(LlmChunk.Text("Длинное предложение."), LlmChunk.Done))
            )
            val h = TurnRunnerHarness(
                llm,
                config = JarvisConfig(
                    maxUtteranceMs = Long.MAX_VALUE,
                    llmTimeoutMs = 400, // shorter than the gated playback
                    ttsSentenceTimeoutMs = 2_000,
                    ttsDrainTimeoutMs = 5_000,
                    llmRetryBackoffMs = 10,
                ),
                playerOverride = GatedPlayer(),
            )
            try {
                val (job, outcome) = h.launchTurn()
                h.deliverUtterance("тест")

                // Correct behaviour: the timeout scope returned after collect,
                // the drain joins the session-scope child, and the sentence
                // times out at ttsSentenceTimeoutMs (2 s) — the turn finishes.
                awaitCond { h.finished.isNotEmpty() }
                job.join()
                assertNull(outcome.await())
                assertTrue(h.failures.isEmpty())
                // Audit fix 8: the sentence TIMED OUT (gated playback never
                // completed — with GatedPlayer no audio ever drains), so it
                // is treated as never spoken: finish carries spoke=false and
                // must not open a follow-up window. (The old flag was set at
                // speakSentence entry and reported spoke=true here.)
                assertEquals(
                    "LLM timeout must not kill parked playback; timed-out sentence is not spoken",
                    listOf(1 to false),
                    h.finished.toList(),
                )
                // The sentence reached playback: it holds a player gate. Note
                // GatedPlayer does NOT collect the synthesis flow, so
                // FakeTtsClient.spoken stays empty by construction — the gate
                // is the observable for "sentence was enqueued".
                assertEquals(1, (h.player as GatedPlayer).gates.size)
            } finally {
                h.shutdown()
            }
        }

    // ------------------------------------------------------------------
    // Event lane sanity: the exact event sequence of a clean text turn and
    // partial-transcript propagation.
    // ------------------------------------------------------------------

    @Test
    fun `clean text turn emits the exact event sequence and propagates partials`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Привет."), LlmChunk.Done)))
        val h = TurnRunnerHarness(llm)
        try {
            val (job, outcome) = h.launchTurn()
            awaitCond { h.asr.streams.isNotEmpty() }
            val stream = h.asr.streams.last()
            stream.awaitEvents()
            stream.emitPartial("при")
            stream.emitPartial("привет")
            stream.emitFinal("привет")

            awaitCond { h.finished.isNotEmpty() }
            job.join()
            assertNull(outcome.await())

            assertEquals(
                listOf(
                    SessionEvent.WakeWordOrBargeIn,
                    SessionEvent.SpeechCaptured,
                    SessionEvent.LlmStarted,
                    SessionEvent.PlaybackStarted,
                ),
                h.events.toList(),
            )
            // Partials flowed live; the final cleared the partial display.
            assertEquals(listOf("при", "привет", ""), h.partials.toList())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `asr open failure reports asrOpenFailed and the turn ends via the funnel`() = runBlocking {
        val h = TurnRunnerHarness(ScriptedLlm(mutableListOf()))
        h.asr.openFailures = 3 // > asrMaxRetries (2)
        try {
            val (job, outcome) = h.launchTurn()
            awaitCond { h.failures.isNotEmpty() }
            job.join()
            assertNull(outcome.await())
            assertEquals(
                listOf(1 to SpeechPhrases.Default.asrOpenFailed),
                h.failures.toList(),
            )
            assertTrue(h.finished.isEmpty())
            assertTrue(h.asr.streams.isEmpty())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `ASR stream failure event reports asrFailed`() = runBlocking {
        val h = TurnRunnerHarness(ScriptedLlm(mutableListOf()))
        try {
            val (job, outcome) = h.launchTurn()
            awaitCond { h.asr.streams.isNotEmpty() }
            val stream = h.asr.streams.last()
            stream.awaitEvents()
            stream.emitFailed()

            awaitCond { h.failures.isNotEmpty() }
            job.join()
            assertNull(outcome.await())
            assertEquals(
                listOf(1 to SpeechPhrases.Default.asrFailed),
                h.failures.toList(),
            )
        } finally {
            h.shutdown()
        }
    }
}

// ---------------------------------------------------------------------------
// Audit fixes 7 + 12: drain scope and NonCancellable write guards.
// ---------------------------------------------------------------------------

class TurnRunnerSentenceDrainTest {

    /**
     * Fix 7: the drain must join ONLY the tracked sentence jobs. A stray
     * session-scope child (launched here from the state-event seam, exactly
     * where a hook/cognitive child would live) must neither stall the drain
     * nor be cancelled by it. The old `coroutineContext[Job]?.children`
     * sweep joined it, parking the turn for the whole drain budget.
     */
    @Test
    fun `drain joins only sentence jobs - a stray session child does not stall or die`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Готово."), LlmChunk.Done)))
        var stray: kotlinx.coroutines.Job? = null
        val harness = TurnRunnerHarness(
            llm,
            config = JarvisConfig(
                maxUtteranceMs = Long.MAX_VALUE,
                ttsSentenceTimeoutMs = 5_000,
                ttsDrainTimeoutMs = 5_000,
                llmTimeoutMs = 10_000,
                llmRetryBackoffMs = 10,
            ),
            onStateEventExtra = { event ->
                if (event == SessionEvent.LlmStarted && stray == null) {
                    // Launch a child of the SESSION job (the runner's current
                    // coroutine context) that hangs forever.
                    stray = kotlinx.coroutines.CoroutineScope(
                        kotlin.coroutines.coroutineContext
                    ).launch { kotlinx.coroutines.awaitCancellation() }
                }
            },
        )
        try {
            val startedAt = System.currentTimeMillis()
            val (job, outcome) = harness.launchTurn()
            harness.deliverUtterance("тест")

            awaitCond { harness.finished.isNotEmpty() }
            val elapsed = System.currentTimeMillis() - startedAt
            // Do NOT job.join() — the stray child would hold the session job
            // open by design; the finish callback is the turn's terminal.
            assertNull(outcome.await())

            assertTrue(
                "drain must not wait for non-sentence children (took $elapsed ms, budget 5000)",
                elapsed < 2_500,
            )
            assertEquals(listOf(1 to true), harness.finished.toList())
            // The stray child is NOT the drain's business — it must survive.
            assertTrue("drain cancelled a non-sentence child", stray!!.isActive)
        } finally {
            harness.shutdown()
        }
    }

    /**
     * Fix 12: the user/assistant history writes re-check the session id
     * INSIDE the NonCancellable block. Here the session is superseded at the
     * SpeechCaptured seam (before the user write) — neither the user row nor
     * the assistant row may land.
     */
    @Test
    fun `superseded session skips the user and assistant writes`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Готово."), LlmChunk.Done)))
        var harness: TurnRunnerHarness? = null
        val h = TurnRunnerHarness(
            llm,
            onStateEventExtra = { event ->
                if (event == SessionEvent.SpeechCaptured) {
                    // Supersede EXACTLY between the user-write's reachability
                    // and the write itself: isCurrentSession flips to false
                    // before the NonCancellable block runs.
                    harness!!.superseded = true
                }
            },
        )
        harness = h
        try {
            val (job, outcome) = h.launchTurn()
            h.deliverUtterance("тест")
            job.join()
            assertNull(outcome.await())

            delay(200) // settle: any (incorrect) write would land here
            assertEquals(
                "superseded session must not write history",
                0,
                h.dao.rows.size,
            )
            assertTrue(h.failures.isEmpty())
        } finally {
            h.shutdown()
        }
    }

    /**
     * P1-S #1 (locked decision 1), EMITTER side: the id [runTurn] was GIVEN is
     * stamped on every event the turn hands to the manager — including
     * `PlaybackStarted`, which comes out of the nested [speakSentence] helper
     * two frames under processLlm. Pre-fix there was no id on the wire at all:
     * the manager's guard read its own current seq, so it compared the session
     * with itself and could never fire.
     *
     * Deliberately launched with id 7 instead of harness.launchTurn()'s 1 (and
     * with isCurrentSession() false for it), so a hardcoded stamp — or one
     * re-read from the "current" session like the manager used to do — could
     * not pass either.
     */
    @Test
    fun `every event of a turn carries the id that turn was launched with`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(listOf(LlmChunk.Text("Привет!"), LlmChunk.Done)),
        )
        val h = TurnRunnerHarness(llm)
        try {
            val job = h.scope.launch { with(h.runner) { runTurn(7) } }
            h.deliverUtterance("Привет, Джарвис")
            awaitCond { h.finished.isNotEmpty() }
            job.join()

            assertTrue("the turn emitted no events at all", h.events.isNotEmpty())
            assertEquals("one seq per emitted event", h.events.size, h.eventSeqs.size)
            assertEquals(setOf(7), h.eventSeqs.toSet())
            assertTrue(
                "no PlaybackStarted means the speech lane never ran",
                h.events.contains(SessionEvent.PlaybackStarted),
            )
            // The extended guard uses the SAME stamp on the shared-UI-state
            // wires: the final's clearing setPartial must carry the turn's id,
            // or the manager cannot tell a live clear from a stale one.
            assertTrue("the turn published no partial at all", h.partialSeqs.isNotEmpty())
            assertEquals(setOf(7), h.partialSeqs.toSet())
        } finally {
            h.shutdown()
        }
    }
}
