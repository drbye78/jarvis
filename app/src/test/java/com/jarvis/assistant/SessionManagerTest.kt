package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.contracts.WakeWordRequest
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.session.SessionEvent
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.session.TurnActivity
import com.jarvis.assistant.session.TurnEvent
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.AsrStream
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.util.OnlineChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

class FakeAsrStream : AsrStream {
    // replay=1 + extraBufferCapacity mirror the production SberStreamingAsr
    // event flow (m11): a terminal event emitted around subscription time
    // must be redelivered, never dropped.
    private val _events = MutableSharedFlow<AsrEvent>(replay = 1, extraBufferCapacity = 16)
    override val events: SharedFlow<AsrEvent> = _events
    val sent = CopyOnWriteArrayList<ByteArray>()

    /** Audit fix regression: transport cancel() count (leak detection). */
    val cancelCount = java.util.concurrent.atomic.AtomicInteger(0)

    override fun send(pcm: ByteArray) {
        sent.add(pcm)
    }

    override fun finish() {}
    override fun cancel() {
        cancelCount.incrementAndGet()
    }

    /** Waits until the manager's collector has subscribed to [events]. */
    suspend fun awaitEvents() {
        withTimeout(5_000) {
            while (_events.subscriptionCount.value == 0) delay(10)
        }
    }

    fun emitPartial(text: String) {
        _events.tryEmit(AsrEvent.Partial(text))
    }

    fun emitFinal(text: String) {
        _events.tryEmit(AsrEvent.Final(text))
    }

    fun emitFailed(cause: Throwable = RuntimeException("server died")) {
        _events.tryEmit(AsrEvent.Failed(cause))
    }
}

class FakeAsrClient : StreamingAsrClient {
    val streams = CopyOnWriteArrayList<FakeAsrStream>()
    var openFailures = 0

    override suspend fun open(): AsrStream {
        if (openFailures > 0) {
            openFailures--
            throw RuntimeException("connection refused")
        }
        return FakeAsrStream().also { streams.add(it) }
    }
}

class ScriptedLlm(private val scripts: MutableList<List<LlmChunk>>) : LlmClient {
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
        requests.add(request)
        val script = if (scripts.isNotEmpty()) scripts.removeAt(0) else listOf(LlmChunk.Done)
        script.forEach { emit(it) }
    }
}

class FakeTtsClient : TtsClient {
    val spoken = CopyOnWriteArrayList<String>()
    val voices = CopyOnWriteArrayList<String>()
    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = flow {
        spoken.add(text)
        voices.add(voice)
        emit(ByteArray(1024))
    }
}

/**
 * G4 harness: an LLM that FAILS the first [failures] attempts with [error]
 * (before emitting anything, so a retry is legal), then streams the script.
 * With [emitBeforeFail] the failing attempt FIRST streams one text chunk —
 * partial output that must NEVER be retried (it would duplicate speech).
 */
class FlakyLlm(
    private val failures: Int,
    private val error: Exception,
    private val failWithPartialOutput: Boolean = false,
) : LlmClient {
    val attempts = java.util.concurrent.atomic.AtomicInteger(0)
    val requests = CopyOnWriteArrayList<ChatRequest>()

    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
        requests.add(request)
        if (attempts.incrementAndGet() <= failures) {
            if (failWithPartialOutput) emit(LlmChunk.Text("Половин"))
            throw error
        }
        emit(LlmChunk.Text("Готово."))
        emit(LlmChunk.Done)
    }
}

/** TTS client whose synthesis never completes (simulates a hung gRPC fetch). */
class HangingTtsClient : TtsClient {
    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> =
        flow { awaitCancellation() }
}

class FakePlayer : TtsPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun play(pcm: Flow<ByteArray>): Deferred<Unit> {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        // The real player drains the flow to the speaker; collect it so cold
        // flows (and their side effects) actually run.
        scope.launch {
            try {
                pcm.collect { }
                done.complete(Unit)
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        return done
    }

    override fun flush() {}
    override fun release() {
        scope.cancel()
    }
}

class FakeWakeWord : WakeWordDetector {
    val detections = MutableSharedFlow<Detection>(extraBufferCapacity = 16)
    override val state = MutableStateFlow<DetectorState>(DetectorState.Ready)
    override fun detections(): Flow<Detection> = detections
    override fun release() {}
    override suspend fun reconfigure(req: WakeWordRequest) {}
    override suspend fun setSensitivity(value: Float) {}

    /** Waits until the manager's collector has subscribed (avoids dropped emissions). */
    suspend fun awaitSubscribed() {
        withTimeout(5_000) {
            while (detections.subscriptionCount.value == 0) delay(10)
        }
    }
}

class FakeOnline : OnlineChecker {
    var online = true
    override fun isCurrentlyOnline(): Boolean = online
}

open class FakeTools(var result: String = """{"status":"ok"}""") : ToolExecutor {
    val executed = CopyOnWriteArrayList<FunctionCall>()

    override fun getToolDefinitions(): List<ToolDefinition> = listOf(
        ToolDefinition(
            name = "setAlarm",
            description = "set an alarm",
            parameters = buildJsonObject { put("type", "object") },
        )
    )

    // 'override' members are open by default, so HangOnNthTools can re-execute.
    override suspend fun executeResult(call: FunctionCall): ToolResult {
        executed.add(call)
        return ToolResult(result, isError = false)
    }
}

/** The first [hangFrom]-1 calls return normally; from the Nth on, hang until cancelled. */
class HangOnNthTools(private val hangFrom: Int) : FakeTools() {
    private val count = java.util.concurrent.atomic.AtomicInteger(0)
    override suspend fun executeResult(call: FunctionCall): ToolResult {
        executed.add(call)
        if (count.incrementAndGet() >= hangFrom) awaitCancellation()
        return ToolResult(result, isError = false)
    }
}

/**
 * Tools with a BOUNDED execution delay — gives turn-activity observers a
 * deterministic window to see ToolRunning while the tool is actually running
 * (a StateFlow collector can otherwise be starved and conflate Thinking →
 * ToolRunning → null, a race slower CI machines exposed).
 */
class SlowTools(private val delayMs: Long) : FakeTools() {
    override suspend fun executeResult(call: FunctionCall): ToolResult {
        executed.add(call)
        delay(delayMs)
        return ToolResult(result, isError = false)
    }
}

/**
 * Player with a manual completion gate per play() call and NO internal
 * collection — lets tests observe exactly how many sentences were enqueued
 * (i.e. how many hold a synthesis permit) before any of them finishes.
 */
class GatedPlayer : TtsPlayer {
    val gates = CopyOnWriteArrayList<kotlinx.coroutines.CompletableDeferred<Unit>>()

    override fun play(pcm: Flow<ByteArray>): Deferred<Unit> =
        kotlinx.coroutines.CompletableDeferred<Unit>().also { gates.add(it) }

    override fun flush() {}
    override fun release() {}
}

/**
 * Audit fix regression fixture: like [GatedPlayer], but flush() has the REAL
 * player's semantics — it cancels the current + queued plays (freeing the
 * sentence jobs awaiting them, and with them the synthesis permits).
 */
class FlushCancellingPlayer : TtsPlayer {
    val gates = CopyOnWriteArrayList<kotlinx.coroutines.CompletableDeferred<Unit>>()

    override fun play(pcm: Flow<ByteArray>): Deferred<Unit> =
        kotlinx.coroutines.CompletableDeferred<Unit>().also { gates.add(it) }

    override fun flush() {
        gates.forEach { it.cancel() }
    }

    override fun release() {
        gates.clear()
    }
}

/** Controllable audio source; silent frames flow through the real pipeline. */
class PumpAudioSource : AudioSource {
    override fun start() {}
    override fun read(): ShortArray = ShortArray(320)
    override fun stop() {}
}

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

private class Harness(
    val llm: ScriptedLlm,
    val config: JarvisConfig = JarvisConfig(
        maxUtteranceMs = Long.MAX_VALUE,
        ttsSentenceTimeoutMs = 5_000,
        ttsDrainTimeoutMs = 5_000,
        llmTimeoutMs = 10_000,
        llmRetryBackoffMs = 50, // fast retries under test
    ),
    toolsOverride: FakeTools? = null,
    ttsOverride: TtsClient? = null,
    playerOverride: TtsPlayer? = null,
    phrasesOverride: com.jarvis.assistant.session.SpeechPhrases? = null,
    /** G4: replaces the scripted LLM entirely (FlakyLlm retry scenarios). */
    llmOverride: LlmClient? = null,
    /** Y6: pinned voice for asserting the per-sentence voiceSource read. */
    voiceSourceOverride: (() -> String)? = null,
    /** COGNITIVE_PLAN 0.2: mutable live voice-stop pref source for toggle tests. */
    voiceStopOverride: (() -> Boolean)? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = FakeMessageDao()
    val conversation = ConversationManager(dao, maxMessages = 20)
    val pipeline = AudioPipeline(scope, PumpAudioSource())
    val stateMachine = SessionStateMachine()
    val asr = FakeAsrClient()
    val wake = FakeWakeWord()
    val tts: TtsClient = ttsOverride ?: FakeTtsClient()
    val player: TtsPlayer = playerOverride ?: FakePlayer()
    val tools: FakeTools = toolsOverride ?: FakeTools()
    val online = FakeOnline()

    val manager = SessionManager(
        audioPipeline = pipeline,
        wakeWordDetector = wake,
        asrClient = asr,
        llm = llmOverride ?: llm,
        ttsClient = tts,
        player = player,
        functionRouter = tools,
        conversationManager = conversation,
        stateMachine = stateMachine,
        networkMonitor = online,
        config = config,
        scope = scope,
        phrases = phrasesOverride ?: com.jarvis.assistant.session.SpeechPhrases.Default,
        voiceSource = voiceSourceOverride ?: { config.ttsVoice },
        voiceStopEnabled = voiceStopOverride ?: { config.voiceStopEnabled },
    )

    fun shutdown() {
        manager.cancelAll()
        pipeline.release()
        scope.cancel()
    }

    /** Emits a wake word, waits for the ASR stream to open, and delivers the transcript. */
    suspend fun runTurn(userText: String) {
        manager.startListening()
        wake.awaitSubscribed()
        wake.detections.emit(Detection.WakeWord)
        withTimeout(5_000) {
            while (asr.streams.isEmpty()) delay(20)
        }
        asr.streams.last().emitFinal(userText)
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

class SessionManagerTest {

    @Test
    fun `happy path - asr final, llm text, tts spoken, idle`() = runBlocking {
        val h = Harness(
            ScriptedLlm(
                mutableListOf(
                    listOf(
                        LlmChunk.Text("Привет!"),
                        LlmChunk.Text(" Чем помочь?"),
                        LlmChunk.Done,
                    )
                )
            )
        )
        try {
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) {
                while (h.conversation.getHistoryForLLM().none { it.role == "assistant" }) delay(20)
            }
            // Wait for the turn to fully drain back to idle.
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            // User message persisted, assistant reply persisted
            val history = h.conversation.getHistoryForLLM()
            assertEquals("user", history[0].role)
            assertEquals("Привет, Джарвис", history[0].content)
            assertEquals("assistant", history.last().role)
            // TTS got both sentences
            val spoken = (h.tts as FakeTtsClient).spoken
            assertTrue(spoken.any { it.contains("Привет") })
            assertTrue(spoken.any { it.contains("Чем помочь") })
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `no speech returns to idle without calling llm`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf())
        val h = Harness(llm)
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.isEmpty()) delay(20)
            }
            h.asr.streams.first().emitFinal("") // EOU with empty transcript

            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            assertEquals(0, llm.requests.size)
            assertEquals(0, h.dao.rows.count { it.role == "assistant" })
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `offline wake word speaks error and stays idle`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf())
        val h = Harness(llm)
        try {
            h.online.online = false
            var error: String? = null
            h.manager.setOnError { error = it }
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)

            withTimeout(5_000) {
                while (error == null) delay(20)
            }
            assertTrue(error!!.contains("интернету"))
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            assertEquals(0, h.asr.streams.size) // never opened ASR
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `spoken phrases flow through the injected provider - not hardcoded`() = runBlocking {
        // i18n wiring: with a custom provider (production passes the
        // resource-backed AndroidSpeechPhrases), the error voice must speak
        // the provider's string — the old hardcoded Russian literal leaked
        // through regardless of locale.
        val fake = object : com.jarvis.assistant.session.SpeechPhrases {
            override val asrOpenFailed = "PHRASE-asrOpenFailed"
            override val asrFailed = "PHRASE-asrFailed"
            override val turnTimeout = "PHRASE-turnTimeout"
            override val networkError = "PHRASE-networkError"
            override val genericError = "PHRASE-genericError"
            override val tooManyToolSteps = "PHRASE-tooManyToolSteps"
            override val llmTimeout = "PHRASE-llmTimeout"
            override val llmFailed = "PHRASE-llmFailed"
            override val offline = "PHRASE-offline"
            override fun wakeWordEngineError(reason: String) = "PHRASE-wake:$reason"
        }
        val llm = ScriptedLlm(mutableListOf())
        val h = Harness(llm, phrasesOverride = fake)
        try {
            h.online.online = false
            var error: String? = null
            h.manager.setOnError { error = it }
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)

            withTimeout(5_000) {
                while (error == null) delay(20)
            }
            assertEquals("PHRASE-offline", error)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `tool loop executes tools and second request carries tool_call_id`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf( // pass 1: model calls the tool
                    LlmChunk.FunctionCallDelta(0, name = "setAlarm", argsDelta = """{"time":"0"""),
                    LlmChunk.FunctionCallComplete(
                        ToolCall(
                            "call_1",
                            function = FunctionCall("setAlarm", """{"time":"07:30"}"""),
                        )
                    ),
                    LlmChunk.Done,
                ),
                listOf( // pass 2: model answers with the tool result in history
                    LlmChunk.Text("Будильник поставлен на 07:30."),
                    LlmChunk.Done,
                ),
            )
        )
        val h = Harness(llm)
        try {
            h.runTurn("поставь будильник на семь тридцать")

            withTimeout(5_000) {
                while (llm.requests.size < 2) delay(20)
            }

            // Tool actually executed once
            assertEquals(listOf("setAlarm"), h.tools.executed.map { it.name })

            // THE original bug #1 regression check: the SECOND request must
            // carry the tool result with tool_call_id linkage and the
            // assistant message with its tool_calls.
            val second = llm.requests[1]
            val toolMsg = second.messages.filter { it.role == "tool" }
            assertEquals(1, toolMsg.size)
            assertEquals("call_1", toolMsg[0].toolCallId)
            assertEquals("setAlarm", toolMsg[0].name)

            val assistantWithCalls =
                second.messages.filter { it.role == "assistant" && !it.toolCalls.isNullOrEmpty() }
            assertEquals(1, assistantWithCalls.size)
            assertEquals("call_1", assistantWithCalls[0].toolCalls!![0].id)

            // Turn completes and returns to idle
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `asr open failure retries then errors`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf())
        val h = Harness(llm)
        try {
            h.asr.openFailures = 3 // more than asrMaxRetries (2)
            var error: String? = null
            h.manager.setOnError { error = it }
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)

            withTimeout(10_000) {
                while (error == null) delay(20)
            }
            assertTrue(error!!.contains("распознавание"))
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `detector error is surfaced instead of running deaf`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf())
        val h = Harness(llm)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.DetectorError("missing model"))

            withTimeout(5_000) {
                while (error == null) delay(20)
            }
            assertTrue(error!!.contains("wake word"))
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `barge-in discards the superseded session's stale history writes`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf( // pass 1: model calls TWO tools; the second never finishes
                    LlmChunk.FunctionCallDelta(0, name = "setAlarm", argsDelta = "{}"),
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_1", function = FunctionCall("setAlarm", "{}"))
                    ),
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_2", function = FunctionCall("getWeather", "{}"))
                    ),
                    LlmChunk.Done,
                )
            )
        )
        val tools = HangOnNthTools(hangFrom = 2) // call_1 completes, call_2 hangs
        val h = Harness(llm, toolsOverride = tools)
        try {
            h.runTurn("поставь будильник и скажи погоду")

            withTimeout(5_000) {
                while (tools.executed.size < 2) delay(20)
            }

            // Barge-in: cancels the session while the second tool is executing.
            h.manager.startSession()
            withTimeout(5_000) {
                while (h.asr.streams.size < 2) delay(20)
            }

            // M1: a superseded session must NOT poison history. The interrupted
            // turn's partial tool-history writes are discarded, even though call_1
            // completed — the user moved on, so its rows are dropped rather than
            // interleaved after the new session's.
            delay(200) // settle: any (incorrect) persistence would land here

            assertEquals(0, h.dao.rows.count { it.role == "tool" })
            assertEquals(
                0,
                h.dao.rows.count { it.role == "assistant" && it.toolCallsJson != null },
            )
            // Only the original user utterance survives (committed before barge-in).
            assertEquals(listOf("user"), h.dao.rows.map { it.role })
            assertEquals(1, llm.requests.size) // never advanced to the second pass
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `three pass tool loop keeps history pairs valid`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf( // pass 1: model calls setAlarm
                    LlmChunk.FunctionCallDelta(0, name = "setAlarm", argsDelta = "{}"),
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_1", function = FunctionCall("setAlarm", "{}"))
                    ),
                    LlmChunk.Done,
                ),
                listOf( // pass 2: model calls getWeather
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_2", function = FunctionCall("getWeather", "{}"))
                    ),
                    LlmChunk.Done,
                ),
                listOf( // pass 3: final answer
                    LlmChunk.Text("Готово."),
                    LlmChunk.Done,
                ),
            )
        )
        val h = Harness(llm)
        try {
            h.runTurn("поставь будильник и скажи погоду")

            withTimeout(5_000) {
                while (llm.requests.size < 3) delay(20)
            }
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }

            assertEquals(3, llm.requests.size)
            assertEquals(listOf("setAlarm", "getWeather"), h.tools.executed.map { it.name })

            // After EACH pass every captured request must carry only VALID
            // pairs: every assistant tool_calls id has its matching tool row,
            // and no orphan tool rows exist.
            for (request in llm.requests) {
                val assistantIds = request.messages
                    .filter { it.role == "assistant" }
                    .flatMapTo(mutableSetOf()) { it.toolCalls.orEmpty().map { c -> c.id } }
                for (msg in request.messages) {
                    if (msg.role == "tool") {
                        assertTrue(
                            "orphan tool row ${msg.toolCallId}",
                            msg.toolCallId != null && msg.toolCallId in assistantIds,
                        )
                    }
                    if (!msg.toolCalls.isNullOrEmpty()) {
                        for (call in msg.toolCalls!!) {
                            assertTrue(
                                "unpaired tool_call ${call.id}",
                                request.messages.any { it.role == "tool" && it.toolCallId == call.id },
                            )
                        }
                    }
                }
            }

            // Final persisted history: user, a(t1), r(t1), a(t2), r(t2), a(final).
            val history = h.conversation.getHistoryForLLM()
            assertEquals(
                listOf("user", "assistant", "tool", "assistant", "tool", "assistant"),
                history.map { it.role },
            )
            assertEquals("call_1", history[1].toolCalls!!.single().id)
            assertEquals("call_1", history[2].toolCallId)
            assertEquals("call_2", history[3].toolCalls!!.single().id)
            assertEquals("call_2", history[4].toolCallId)
            assertEquals(null, history[5].toolCalls)
            assertEquals("Готово.", history[5].content)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `detector failed state surfaces error and starts no session`() = runBlocking {
        val llm = ScriptedLlm(mutableListOf())
        val h = Harness(llm)
        try {
            h.wake.state.value = DetectorState.Failed("missing model")
            var error: String? = null
            h.manager.setOnError { error = it }
            h.manager.startListening()

            withTimeout(5_000) {
                while (error == null) delay(20)
            }
            assertTrue(error!!.contains("wake word"))
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            assertEquals(0, h.asr.streams.size) // no listening session started
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------------
    // P3: stale-session isolation (M6), drain (m9), prefetch (m10),
    // partials (S1), mute (m12)
    // ------------------------------------------------------------------------

    @Test
    fun `stale session late error is dropped and leaves newer session untouched`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf()))
        try {
            var errors = 0
            h.manager.setOnError { errors++ }

            h.manager.startSession() // seq 1
            h.manager.startSession() // seq 2 supersedes it (barge-in)
            // startSession launches the LISTENING transition asynchronously in a
            // child coroutine; wait for it rather than racing the scheduler.
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.LISTENING) delay(20)
            }

            // A late failure from the SUPERSEDED session must be logged and
            // dropped: no error handler, no state-machine reset.
            h.manager.reportFailure(1, "поздний сбой устаревшей сессии")
            assertEquals(0, errors)
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())

            // The CURRENT session's failure still surfaces.
            h.manager.reportFailure(2, "сбой текущей сессии")
            assertEquals(1, errors)
            // P1-S: the guard + the ErrorOccurred transition are ONE synchronous
            // call on this thread (no event lane, no consumer, no launch hop),
            // so the reset is observable the instant reportFailure returns —
            // asserting it directly (rather than polling a supposedly async
            // drain) is what pins that contract.
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `cancelAll transitions wedged machine to idle`() = runBlocking {
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Один."), LlmChunk.Text("Два."), LlmChunk.Done))),
            ttsOverride = HangingTtsClient(), // sentences hang => machine wedges in SPEAKING
        )
        try {
            h.runTurn("расскажи что-нибудь")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.SPEAKING) delay(20)
            }
            // Cancellation alone emits nothing; cancelAll must explicitly and
            // safely bring the machine back to IDLE.
            h.manager.cancelAll()
            // P1-S: the seq bump happens INSIDE [controlLock] and the Cancelled
            // apply is a synchronous call on this thread — no launch hop, no
            // consumer queue. The dying turn's trailing finish() (LlmDone) now
            // carries the superseded seq, so it is dropped and can neither
            // precede nor undo this reset: IDLE holds, not merely arrives.
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            delay(300) // let the cancelled turn's teardown + trailing terminals run
            assertEquals(
                "a superseded turn's late terminal event moved the machine",
                AssistantState.IDLE,
                h.stateMachine.currentState(),
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `drain finishes under one overall deadline when sentences hang`() = runBlocking {
        val config = JarvisConfig(

            maxUtteranceMs = Long.MAX_VALUE,
            ttsSentenceTimeoutMs = 30_000, // sentence timeout must NOT rescue the drain
            ttsDrainTimeoutMs = 600, // ONE overall budget for all children
            llmTimeoutMs = 10_000,
        )
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("А."), LlmChunk.Text("Б."), LlmChunk.Done))),
            config = config,
            ttsOverride = HangingTtsClient(),
        )
        try {
            val startedAt = System.currentTimeMillis()
            launch {
                var last: AssistantState? = null
                while (System.currentTimeMillis() - startedAt < 4_000) {
                    val s = h.stateMachine.currentState()
                    last = s
                    delay(20)
                }
            }
            h.runTurn("два предложения")
            // Old sequential-per-child behaviour parks >= 2 x 600 ms in
            // SPEAKING; the single-overall-budget drain must reach IDLE well
            // inside that (and always reaches IDLE at all).
            withTimeout(3_500) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            val elapsed = System.currentTimeMillis() - startedAt
            assertTrue("drain took $elapsed ms, expected under the single 600 ms budget + slack", elapsed < 1_000)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `tts synthesis prefetch is bounded to two concurrent sentences`() = runBlocking {
        val h = Harness(
            ScriptedLlm(
                mutableListOf(
                    listOf(
                        LlmChunk.Text("one."),
                        LlmChunk.Text("two."),
                        LlmChunk.Text("three."),
                        LlmChunk.Done,
                    )
                )
            ),
            playerOverride = GatedPlayer(),
        )
        val gated = h.player as GatedPlayer
        try {
            h.runTurn("три предложения")

            // Sentences 1 and 2 take the two permits; sentence 3 must WAIT
            // instead of opening a third stream up front.
            withTimeout(5_000) { while (gated.gates.size < 2) delay(20) }
            delay(200) // settle: an unbounded implementation would enqueue #3 here
            assertEquals("prefetch not bounded", 2, gated.gates.size)

            // Completing one sentence frees a permit; the third proceeds.
            gated.gates[0].complete(Unit)
            withTimeout(5_000) { while (gated.gates.size < 3) delay(20) }

            gated.gates[1].complete(Unit)
            gated.gates[2].complete(Unit)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `partial transcript updates on partial and clears on final and error`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf(listOf(LlmChunk.Done))))
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            val stream = h.asr.streams.first()
            stream.awaitEvents()

            stream.emitPartial("при")
            withTimeout(5_000) { while (h.manager.partialTranscript.value != "при") delay(10) }
            stream.emitPartial("привет")
            withTimeout(5_000) { while (h.manager.partialTranscript.value != "привет") delay(10) }

            // Final clears the partial.
            stream.emitFinal("привет")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            assertEquals("", h.manager.partialTranscript.value)

            // Let the global post-accept debounce (600 ms) elapse so the next
            // wake word is a fresh activation, not a trailing echo of turn one.
            delay(700)

            // Second turn: a server failure clears the partial too.
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.size < 2) delay(20) }
            val stream2 = h.asr.streams.last()
            stream2.awaitEvents()
            stream2.emitPartial("пог")
            withTimeout(5_000) { while (h.manager.partialTranscript.value != "пог") delay(10) }
            stream2.emitFailed()
            // The AsrFailed transition (LISTENING → IDLE) and the partial
            // clear in reportFailure happen back-to-back in the turn
            // coroutine — the state can be observed as IDLE a hair BEFORE
            // the clear lands on another core (a pre-existing micro-race the
            // full-suite load exposes). Poll for the CLEAR (the thing under
            // test) and then assert the terminal state.
            withTimeout(5_000) {
                while (h.manager.partialTranscript.value.isNotEmpty()) delay(10)
            }
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            assertEquals("", h.manager.partialTranscript.value)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `mute cancels active session stops pipeline and survives power reconnect`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf(listOf(LlmChunk.Done))))
        try {
            h.pipeline.start()
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
            assertTrue(h.pipeline.isRunning())

            // Muting is a user intent: pipeline stops AND active session dies.
            h.manager.setMuted(true)
            assertTrue(h.manager.muted.value)
            // cancelAll launches onEvent(Cancelled) on the session scope; wait
            // for the state machine to reach IDLE (mirrors the cancelAll test).
            withTimeout(5_000) { while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20) }
            assertFalse(h.pipeline.isRunning())

            // Power receiver fires CONNECTED while muted: restart respects mute.
            h.manager.onPowerConnected()
            assertFalse("power receiver silently unmuted", h.pipeline.isRunning())

            // Unmuting restores pipeline + wake-word collection.
            h.manager.setMuted(false)
            assertFalse(h.manager.muted.value)
            assertTrue(h.pipeline.isRunning())
            h.wake.awaitSubscribed()
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // G4: transient LLM failures are retried (zero output only)
    // ------------------------------------------------------------------

    @Test
    fun `transient LLM failure with no output is retried and turn completes`() = runBlocking {
        val flaky = FlakyLlm(failures = 1, error = java.io.IOException("connection reset"))
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = flaky)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            // First attempt failed, retry succeeded.
            assertEquals(2, flaky.attempts.get())
            // No error voice: the turn completed normally.
            assertEquals(null, error)
            assertTrue(
                h.conversation.getHistoryForLLM().any { it.role == "assistant" && it.content.contains("Готово") }
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `retry budget exhausted speaks the error once`() = runBlocking {
        // Default llmMaxRetries = 1 → two attempts total, both fail.
        val flaky = FlakyLlm(failures = 2, error = java.io.IOException("connection reset"))
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = flaky)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) { while (error == null) delay(20) }
            assertEquals(2, flaky.attempts.get()) // no third attempt
            assertTrue(error!!.contains("нейросети")) // phrase_llmFailed
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `partial output is never retried`() = runBlocking {
        // The failing attempt EMITS a text chunk first — a retry would
        // duplicate spoken sentences, so the turn must fail immediately.
        val flaky = FlakyLlm(
            failures = 1,
            error = java.io.IOException("mid-stream drop"),
            failWithPartialOutput = true,
        )
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = flaky)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) { while (error == null) delay(20) }
            assertEquals(1, flaky.attempts.get()) // never retried
            assertTrue(error!!.contains("нейросети"))
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `HTTP 401 is fatal and not retried`() = runBlocking {
        val flaky = FlakyLlm(failures = 1, error = com.jarvis.assistant.llm.LlmHttpException(401))
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = flaky)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) { while (error == null) delay(20) }
            assertEquals(1, flaky.attempts.get()) // 4xx: fail fast
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `HTTP 503 is transient and retried`() = runBlocking {
        val flaky = FlakyLlm(failures = 1, error = com.jarvis.assistant.llm.LlmHttpException(503))
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = flaky)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            assertEquals(2, flaky.attempts.get()) // 5xx: one retry
            assertEquals(null, error)
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // G3: turn activity drives the THINKING status pill
    // ------------------------------------------------------------------

    @Test
    fun `tool run publishes ToolRunning and clears at turn end`() = runBlocking {
        // Pass 1 emits the tool call, then PAUSES mid-stream: that gives the
        // collector a guaranteed window to observe Thinking before ToolRunning
        // (both are StateFlow writes < 1 ms apart without the pause, and a
        // starved collector conflates them — a race slower machines exposed).
        val llm = object : LlmClient {
            var pass = 0
            override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
                pass++
                if (pass == 1) {
                    emit(
                        LlmChunk.FunctionCallComplete(
                            ToolCall("call_1", function = FunctionCall("setAlarm", """{"time":"07:30"}"""))
                        )
                    )
                    delay(300) // Thinking-observation window
                    emit(LlmChunk.Done)
                } else {
                    emit(LlmChunk.Text("Будильник поставлен."))
                    emit(LlmChunk.Done)
                }
            }
        }
        // Bounded tool delay: ToolRunning must be OBSERVABLE while the tool
        // runs (not conflated away between collector resumptions).
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = llm, toolsOverride = SlowTools(delayMs = 250))
        try {
            val seen = CopyOnWriteArrayList<com.jarvis.assistant.session.TurnActivity?>()
            val job = launch {
                h.manager.turnActivity.collect { seen.add(it) }
            }
            // StateFlow emits its current value to a new subscriber — the
            // first [null] proves the collector is live BEFORE the turn runs.
            withTimeout(5_000) { while (seen.isEmpty()) delay(10) }
            h.runTurn("поставь будильник на семь тридцать")

            // The tool label must arrive while the tool is still running.
            withTimeout(5_000) {
                while (
                    seen.none { it is com.jarvis.assistant.session.TurnActivity.ToolRunning }
                    ) delay(10)
            }
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            job.cancel()
            // Thinking precedes the tool label; the label carries the tool name.
            assertTrue("no Thinking seen: $seen", seen.any { it is com.jarvis.assistant.session.TurnActivity.Thinking })
            val tool = seen.filterIsInstance<com.jarvis.assistant.session.TurnActivity.ToolRunning>()
            assertEquals(1, tool.size)
            assertEquals("setAlarm", tool[0].tool)
            // Cleared (null) by the terminal.
            assertEquals(null, seen.last())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `failed turn clears the activity label`() = runBlocking {
        val flaky = FlakyLlm(failures = 2, error = java.io.IOException("dead"))
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = flaky)
        try {
            var error: String? = null
            h.manager.setOnError { error = it }
            h.runTurn("Привет, Джарвис")

            withTimeout(5_000) { while (error == null) delay(20) }
            assertEquals(null, h.manager.turnActivity.value)
        } finally {
            h.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // G1 + Y6: composed system prompt and per-sentence voice
    // ------------------------------------------------------------------

    @Test
    fun `request carries the composed time-aware system prompt`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(listOf(LlmChunk.Text("Готово."), LlmChunk.Done))
        )
        val h = Harness(llm)
        try {
            h.runTurn("Привет, Джарвис")
            withTimeout(5_000) {
                while (llm.requests.isEmpty()) delay(20)
            }
            val first = llm.requests[0].messages.first()
            assertEquals("system", first.role)
            // Identity + live time context + policies all present.
            assertTrue(first.content.contains("Ты — Джарвис"))
            assertTrue(Regex("Сейчас \\d{2}:\\d{2},").containsMatchIn(first.content))
            assertTrue(first.content.contains("уточняющий вопрос"))
            assertTrue(first.content.contains("playMusic"))
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `voice is resolved per sentence from the injected source`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(listOf(LlmChunk.Text("Готово."), LlmChunk.Done))
        )
        val h = Harness(llm, voiceSourceOverride = { "Anton" })
        try {
            h.runTurn("Привет, Джарвис")
            withTimeout(5_000) {
                while ((h.tts as FakeTtsClient).spoken.isEmpty() && h.stateMachine.currentState() != AssistantState.IDLE) delay(
                    20
                )
            }
            assertEquals(listOf("Anton"), (h.tts as FakeTtsClient).voices)
        } finally {
            h.shutdown()
        }
    }
}

// ---------------------------------------------------------------------------
// FIXPLAN B: voice stop without the wake word
// ---------------------------------------------------------------------------

/** LLM that never produces anything — parks the turn in THINKING. */
private class HangingLlm : LlmClient {
    override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
        flow { awaitCancellation() }
}

class VoiceStopSessionTest {

    @Test
    fun `stop phrase during THINKING cancels the turn and goes IDLE`() = runBlocking {
        val h = Harness(llm = ScriptedLlm(mutableListOf()), llmOverride = HangingLlm())
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.isEmpty()) delay(20)
            }
            // Deliver the transcript: ASR final -> THINKING (LLM hangs there).
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.THINKING) delay(10)
            }

            h.wake.detections.emit(Detection.StopPhrase("stop"))

            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(10)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `stop phrase during SPEAKING stops playback and goes IDLE`() = runBlocking {
        // Gated player parks the sentence in playback → durable SPEAKING.
        val player = GatedPlayer()
        val h = Harness(
            llm = ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Длинный ответ."), LlmChunk.Done))),
            playerOverride = player,
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.isEmpty()) delay(20)
            }
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.SPEAKING) delay(10)
            }
            assertEquals(1, player.gates.size)

            h.wake.detections.emit(Detection.StopPhrase("stop"))

            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(10)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `stop phrase in LISTENING is ignored - it is part of the user's utterance`() = runBlocking {
        val h = Harness(
            llm = ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Привет."), LlmChunk.Done))),
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.LISTENING) delay(10)
            }

            h.wake.detections.emit(Detection.StopPhrase("stop"))
            delay(250) // would be enough for a cancel to land if one were coming

            // The turn is untouched: still LISTENING, ASR stream still open.
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
            assertTrue(h.asr.streams.isNotEmpty())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `wake collector survives a voice stop - unlike cancelAll`() = runBlocking {
        // THE differentiator vs cancelAll: after a stop the wake-word
        // collector must still be subscribed, so the next wake word starts
        // a fresh session.
        val h = Harness(llm = ScriptedLlm(mutableListOf()), llmOverride = HangingLlm())
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.isEmpty()) delay(20)
            }
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.THINKING) delay(10)
            }
            h.wake.detections.emit(Detection.StopPhrase("stop"))
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(10)
            }

            // Beyond the post-accept wake cooldown (600 ms) that the first
            // accepted wake word started — the cooldown is per-GESTURE and a
            // stop phrase does not (and must not) consume it.
            delay(700)
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.LISTENING) delay(10)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `stopActiveTurn with nothing active is a safe no-op`() = runBlocking {
        val h = Harness(llm = ScriptedLlm(mutableListOf()))
        try {
            h.manager.stopActiveTurn()
            delay(100)
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
        } finally {
            h.shutdown()
        }
    }
}

// ---------------------------------------------------------------------------
// COGNITIVE_PLAN 0.2: the voice-stop toggle must be honoured LIVE, in every
// engine×toggle combination and at any moment of the turn. The lane's armed
// state can lag a Settings flip; the handler re-checks the live pref so a
// stale-armed lane can never cancel a turn the user no longer wants
// interruptible.
// ---------------------------------------------------------------------------

class SessionManagerVoiceStopToggleTest {

    @Test
    fun `toggle OFF mid-THINKING - stale stop phrase does not cancel the turn`() = runBlocking {
        var voiceStop = true
        val h = Harness(
            llm = ScriptedLlm(mutableListOf()),
            llmOverride = HangingLlm(),
            voiceStopOverride = { voiceStop },
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.THINKING) delay(10)
            }

            // The user disables voice stop WHILE the assistant is thinking —
            // no state change happens, so a lane armed under the old pref may
            // still be live. The handler must re-check the pref.
            voiceStop = false
            h.wake.detections.emit(Detection.StopPhrase("stop"))
            delay(250) // would be ample for a cancel to land

            assertEquals(
                "stop with the toggle OFF must be ignored",
                AssistantState.THINKING,
                h.stateMachine.currentState(),
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `toggle back ON mid-THINKING - the next stop phrase cancels again`() = runBlocking {
        var voiceStop = true
        val h = Harness(
            llm = ScriptedLlm(mutableListOf()),
            llmOverride = HangingLlm(),
            voiceStopOverride = { voiceStop },
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.THINKING) delay(10)
            }

            voiceStop = false
            h.wake.detections.emit(Detection.StopPhrase("stop"))
            delay(150)
            assertEquals(AssistantState.THINKING, h.stateMachine.currentState())

            voiceStop = true
            h.wake.detections.emit(Detection.StopPhrase("stop"))
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(10)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `stop OFF from the start is ignored - ON mid-turn is honoured (combo matrix)`() = runBlocking {
        var voiceStop = false
        val h = Harness(
            llm = ScriptedLlm(mutableListOf()),
            llmOverride = HangingLlm(),
            voiceStopOverride = { voiceStop },
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.THINKING) delay(10)
            }

            h.wake.detections.emit(Detection.StopPhrase("stop"))
            delay(200)
            assertEquals(
                "disabled from the start — never cancels",
                AssistantState.THINKING,
                h.stateMachine.currentState()
            )

            voiceStop = true
            h.wake.detections.emit(Detection.StopPhrase("stop"))
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(10)
            }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `toggle OFF mid-SPEAKING - queued speech is not interrupted by stop`() = runBlocking {
        var voiceStop = true
        // Gated player parks the sentence in playback → durable SPEAKING.
        val player = GatedPlayer()
        val h = Harness(
            llm = ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Длинный ответ."), LlmChunk.Done))),
            playerOverride = player,
            voiceStopOverride = { voiceStop },
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            h.asr.streams.last().emitFinal("тест")
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.SPEAKING) delay(10)
            }

            voiceStop = false
            h.wake.detections.emit(Detection.StopPhrase("stop"))
            delay(250)
            assertEquals(
                "the user disabled interruption — playback continues",
                AssistantState.SPEAKING,
                h.stateMachine.currentState(),
            )
        } finally {
            h.shutdown()
        }
    }
}

// ---------------------------------------------------------------------------
// COGNITIVE_PLAN 2.4: proactive delivery (a guarded mini-session)
// ---------------------------------------------------------------------------

class SessionManagerProactiveTest {

    private val suggestion = "Ты обычно слушаешь джаз в это время. Включить?"

    @Test
    fun `speakProactively refuses when the machine is not IDLE`() = runBlocking {
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Ок."), LlmChunk.Done))),
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.LISTENING) delay(10)
            }
            assertEquals(false, h.manager.speakProactively(suggestion))
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `speakProactively speaks, persists with the proactive marker, opens the window`() =
        runBlocking {
            val h = Harness(
                ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Ок."), LlmChunk.Done))),
            )
            // The state PATH is what this test is really about: the proactive
            // utterance must drain through the SAME terminal a normal turn uses
            // (SPEAKING → IDLE) and only then open the window. A collector on
            // the machine's flow makes that observable (P1-S #9: the assertion
            // this replaces was `assertTrue(true)`).
            val path = CopyOnWriteArrayList<AssistantState>()
            val watcher = h.scope.launch {
                h.stateMachine.state.collect { path.add(it) }
            }
            delay(50) // let the watcher subscribe before anything moves
            try {
                assertTrue(h.manager.speakProactively(suggestion))
                withTimeout(5_000) {
                    while (h.stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW) delay(10)
                }
                // The template reached the TTS lane…
                assertTrue((h.tts as FakeTtsClient).spoken.contains(suggestion))
                // …the suggestion is persisted FIRST with the proactive marker…
                withTimeout(5_000) {
                    while (h.dao.rows.isEmpty()) delay(10)
                }
                assertEquals("proactive", h.dao.rows.last().name)
                assertEquals(suggestion, h.dao.rows.last().content)
                // …and the drain landed through the normal SPEAKING → IDLE edge
                // (no SPEAKING → FOLLOW_UP_WINDOW shortcut, no window before the
                // audio actually drained).
                // P1-S (conflation fix): the terminal applies LlmDone (→ IDLE)
                // and FollowUpWindowOpened (→ FOLLOW_UP_WINDOW) back-to-back
                // with NO suspension between them, so a StateFlow collector may
                // legitimately CONFLATE the intermediate IDLE (and under load
                // even the SPEAKING leg) — an exact 4-element path list is not
                // observable and hangs `while (path.size < 4)`. What the flow
                // CAN honestly prove: every observed state rides the legal
                // proactive path in order (a subsequence of it), the run starts
                // from IDLE, and the window is the final observation. Reaching
                // FOLLOW_UP_WINDOW AT ALL (asserted above via currentState())
                // already pins the SPEAKING → IDLE leg: SessionTransitions has
                // no SPEAKING → FOLLOW_UP_WINDOW edge, so any shortcut would
                // have been rejected by the machine and the state wait above
                // would have timed out instead.
                withTimeout(5_000) {
                    while (!path.contains(AssistantState.FOLLOW_UP_WINDOW)) delay(20)
                }
                val observed = path.toList()
                assertEquals(
                    "proactive path must start from IDLE",
                    AssistantState.IDLE,
                    observed.first(),
                )
                assertTrue(
                    "observed states must ride the legal proactive path in order: $observed",
                    observed.isSubsequenceOf(
                        listOf(
                            AssistantState.IDLE,
                            AssistantState.SPEAKING,
                            AssistantState.IDLE,
                            AssistantState.FOLLOW_UP_WINDOW,
                        ),
                    ),
                )
                assertEquals(
                    "the window must be observed exactly once, at the end: $observed",
                    1,
                    observed.count { it == AssistantState.FOLLOW_UP_WINDOW },
                )
            } finally {
                watcher.cancel()
                h.shutdown()
            }
        }

    @Test
    fun `a blank suggestion is refused without touching the machine`() = runBlocking {
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Ок."), LlmChunk.Done))),
        )
        try {
            assertEquals(false, h.manager.speakProactively("   "))
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
        } finally {
            h.shutdown()
        }
    }

    /** `this` is a subsequence of [superset] (order-preserving, gaps allowed). */
    private fun <T> List<T>.isSubsequenceOf(superset: List<T>): Boolean {
        var i = 0
        for (candidate in superset) {
            if (i < size && this[i] == candidate) i++
        }
        return i == size
    }
}

// ---------------------------------------------------------------------------
// Audit-fix regression batch (session/turn lanes): seq-guard stale events,
// mute-gated proactive lane, spoke-flag honesty, TTS timeout cancellation,
// ASR transport teardown, follow-up-window open guards.
// ---------------------------------------------------------------------------

class SessionManagerAuditFixTest {

    /**
     * LLM that streams two sentences, pauses (so they enqueue and park on
     * their player gates), then streams a THIRD sentence and fails the
     * stream — the post-partial failure path (no retry allowed).
     */
    private class PartialThenFailingLlm : LlmClient {
        override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
            emit(LlmChunk.Text("Первое предложение."))
            emit(LlmChunk.Text("Второе предложение."))
            delay(300) // sentences 1–2 enqueue and park on their gates
            emit(LlmChunk.Text("Третье предложение."))
            throw java.io.IOException("mid-stream drop")
        }
    }

    /** TTS whose synthesis flow always explodes (collected by the player). */
    private class ExplodingTtsClient : TtsClient {
        override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = flow {
            throw java.io.IOException("synthesis exploded")
        }
    }

    /**
     * Mic fake with a speech toggle: silent until flipped, then continuous
     * LOUD frames (EnergyVad onset territory) — drives the follow-up
     * collector's VAD onset path deterministically.
     */
    private class ToggleableSpeechSource : AudioSource {
        @Volatile var speech = false
        override fun start() {
            // lifecycle only — frames flow from the first read()
        }

        override fun stop() {
            // lifecycle only — pipeline.release() ends the producer loop
        }

        override fun read(): ShortArray {
            // Real-time pacing (20 ms frames): a busy-loop fake would starve
            // the dispatcher and flake the collectors.
            Thread.sleep(20)
            val amp = if (speech) 3000 else 0
            return ShortArray(320) { (if (it % 2 == 0) amp else -amp).toShort() }
        }
    }

    /**
     * Manager wired to [ToggleableSpeechSource] — the main [Harness] pins a
     * silent source, but the F2 onset tests need VAD-onset-grade frames.
     */
    private class VadHarness(source: ToggleableSpeechSource) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pipeline = AudioPipeline(scope, source)
        val stateMachine = SessionStateMachine()
        val asr = FakeAsrClient()
        val wake = FakeWakeWord()
        val manager = SessionManager(
            audioPipeline = pipeline,
            wakeWordDetector = wake,
            asrClient = asr,
            llm = ScriptedLlm(mutableListOf()),
            ttsClient = FakeTtsClient(),
            player = FakePlayer(),
            functionRouter = FakeTools(),
            conversationManager = ConversationManager(FakeMessageDao(), maxMessages = 20),
            stateMachine = stateMachine,
            networkMonitor = FakeOnline(),
            config = JarvisConfig(
                maxUtteranceMs = Long.MAX_VALUE,
                ttsSentenceTimeoutMs = 5_000,
                ttsDrainTimeoutMs = 5_000,
                llmTimeoutMs = 10_000,
            ),
            scope = scope,
        )

        fun shutdown() {
            manager.cancelAll()
            pipeline.release()
            scope.cancel()
        }
    }

    // ----------------------------------------------------------------------
    // Fix 6: LLM failure after partial output must cancel + join the speech
    // children BEFORE reportFailure's flush — a pending sentence (waiting on
    // a synthesis permit) that survives the failure acquires the permit once
    // the flush cancels the parked plays and enqueues a Play AFTER the flush,
    // playing over the error voice.
    // ----------------------------------------------------------------------

    @Test
    fun `LLM failure after partial output cancels pending speech - nothing enqueues after the flush`() =
        runBlocking {
            val h = Harness(
                ScriptedLlm(mutableListOf()),
                llmOverride = PartialThenFailingLlm(),
                playerOverride = FlushCancellingPlayer(),
            )
            val player = h.player as FlushCancellingPlayer
            try {
                var error: String? = null
                h.manager.setOnError { error = it }
                h.runTurn("расскажи что-нибудь")

                // Sentences 1–2 parked on their gates (permits exhausted),
                // sentence 3 waiting on a synthesis permit.
                withTimeout(5_000) { while (player.gates.size < 2) delay(20) }
                withTimeout(5_000) { while (error == null) delay(20) }

                // The error funnel ran its flush. A pending sentence that
                // survived it would acquire the freed permit now and enqueue
                // a third Play — playing over the error voice.
                delay(500) // settle: any (incorrect) post-flush enqueue lands here
                assertEquals(
                    "a pending sentence enqueued after the flush",
                    2,
                    player.gates.size,
                )
                withTimeout(5_000) {
                    while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
                }
            } finally {
                h.shutdown()
            }
        }

    // ----------------------------------------------------------------------
    // Fix 8: the spoke flag must reflect audio that actually reached
    // playback — a turn whose every sentence failed must NOT open a
    // follow-up window for audio the user never heard.
    // ----------------------------------------------------------------------

    @Test
    fun `a turn whose sentences all fail to play opens no follow-up window`() = runBlocking {
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Привет."), LlmChunk.Done))),
            ttsOverride = ExplodingTtsClient(),
        )
        try {
            h.manager.setFollowUpWindow(enabled = true, windowMs = 8_000)
            h.runTurn("привет")

            // The turn ends (LlmDone → IDLE) with spoke=false: no window.
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
            delay(1_500) // long enough for a would-be window to have opened
            assertEquals(
                "no follow-up window after a never-spoken turn",
                AssistantState.IDLE,
                h.stateMachine.currentState(),
            )
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
        } finally {
            h.shutdown()
        }
    }

    // ----------------------------------------------------------------------
    // Fix 9: the per-sentence timeout must CANCEL the playback (stop the
    // audio at the budget) instead of abandoning the deferred.
    // ----------------------------------------------------------------------

    @Test
    fun `TTS sentence timeout cancels the playback instead of abandoning it`() = runBlocking {
        val player = GatedPlayer() // playback never completes on its own
        val config = JarvisConfig(
            maxUtteranceMs = Long.MAX_VALUE,
            ttsSentenceTimeoutMs = 300, // short budget
            ttsDrainTimeoutMs = 5_000,
            llmTimeoutMs = 10_000,
        )
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Длинный ответ."), LlmChunk.Done))),
            config = config,
            playerOverride = player,
        )
        try {
            h.runTurn("расскажи что-нибудь")
            withTimeout(5_000) { while (player.gates.isEmpty()) delay(20) }

            // At the budget the sentence's player deferred must be cancelled…
            withTimeout(5_000) { while (!player.gates[0].isCancelled) delay(20) }
            // …and the turn must still terminate cleanly.
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }
        } finally {
            h.shutdown()
        }
    }

    // ----------------------------------------------------------------------
    // Fix 4: a COMPLETED session job left in the field is not an active
    // turn — stopActiveTurn must not bump the seq / apply a global Cancelled
    // over an open follow-up window; it must CLOSE the window instead.
    // ----------------------------------------------------------------------

    @Test
    fun `stopActiveTurn after a cleanly finished turn closes the open follow-up window`() =
        runBlocking {
            val h = Harness(
                ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Готово."), LlmChunk.Done))),
            )
            try {
                h.manager.setFollowUpWindow(enabled = true, windowMs = 8_000)
                h.runTurn("привет")
                withTimeout(5_000) {
                    while (h.stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW) delay(20)
                }

                // The session job already COMPLETED — the old
                // `sessionJob != null` check still counted it as active.
                h.manager.stopActiveTurn()

                // The window is properly closed (not Cancelled-over while the
                // collector keeps running).
                withTimeout(5_000) { while (h.manager.followUpProgress.value != 0f) delay(20) }
                assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
                delay(300) // a surviving collector would re-fire here
                assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            } finally {
                h.shutdown()
            }
        }

    // ----------------------------------------------------------------------
    // Fix 5: a muted assistant has a STOPPED pipeline — the proactive lane
    // must refuse instead of speaking into silence and force-opening a
    // follow-up window.
    // ----------------------------------------------------------------------

    @Test
    fun `speakProactively refuses while muted`() = runBlocking {
        val suggestion = "Ты обычно слушаешь джаз в это время. Включить?"
        val h = Harness(
            ScriptedLlm(mutableListOf(listOf(LlmChunk.Text("Ок."), LlmChunk.Done))),
        )
        try {
            h.pipeline.start()
            h.manager.setMuted(true)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }

            assertEquals(false, h.manager.speakProactively(suggestion))
            delay(300) // any (incorrect) mini-session would have applied by now
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            assertEquals("muted proactive must not synthesize", 0, (h.tts as FakeTtsClient).spoken.size)
            assertEquals("muted proactive must not persist", 0, h.dao.rows.size)
        } finally {
            h.shutdown()
        }
    }

    // ----------------------------------------------------------------------
    // Fix 13: the ASR bidi transport must be cancelled on EVERY exit — a
    // barge-in mid-utterance previously abandoned the RPC until its deadline.
    // ----------------------------------------------------------------------

    @Test
    fun `barge-in mid-utterance cancels the ASR transport`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf()))
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }
            val first = h.asr.streams.first()

            h.manager.startSession() // barge-in while the user is mid-utterance
            withTimeout(5_000) { while (h.asr.streams.size < 2) delay(20) }

            withTimeout(5_000) {
                while (first.cancelCount.get() == 0) delay(20)
            }
        } finally {
            h.shutdown()
        }
    }

    // ----------------------------------------------------------------------
    // Fix 1 (white-box): the follow-up open carries the apply-time seq guard,
    // and the VAD collector bails unless the open actually applied.
    // maybeOpenFollowUpWindow is internal precisely for these two tests.
    // ----------------------------------------------------------------------

    @Test
    fun `stale follow-up window open is fully dropped - no event no collector`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf()))
        try {
            // id=999 never existed → stale by construction. Pre-fix this
            // applied FollowUpWindowOpened (IDLE → FOLLOW_UP_WINDOW) and
            // launched a live VAD collector from a phantom turn.
            h.manager.maybeOpenFollowUpWindow(spoke = true, id = 999, forceOpen = true)
            delay(300)
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `follow-up collector bails when the open did not apply - no VAD lane over a live session`() =
        runBlocking {
            val h = Harness(ScriptedLlm(mutableListOf()))
            try {
                h.manager.startSession() // fresh session owns the machine → LISTENING
                withTimeout(5_000) {
                    while (h.stateMachine.currentState() != AssistantState.LISTENING) delay(20)
                }

                // Current seq (the open's seq guard passes) but the machine
                // REJECTS FollowUpWindowOpened from LISTENING — the collector
                // must bail instead of racing the live ASR lane.
                h.manager.maybeOpenFollowUpWindow(spoke = true, id = 1, forceOpen = true)
                delay(300)
                assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
                assertEquals(
                    "a VAD collector was launched over a live session",
                    0f,
                    h.manager.followUpProgress.value,
                    0f,
                )
            } finally {
                h.shutdown()
            }
        }

    // ----------------------------------------------------------------------
    // Remediation F2: the VAD-onset path re-validates the open's seq AND the
    // machine state before firing startSession(fromFollowUp = true) — a
    // collector still draining its last frames after a supersede/close must
    // never start a session over the live ASR (or from IDLE).
    // ----------------------------------------------------------------------

    @Test
    fun `ghost VAD onset after the window closed must not start a session`() = runBlocking {
        val source = ToggleableSpeechSource()
        val h = VadHarness(source)
        try {
            h.pipeline.start() // silent frames flow
            // White-box open: current seq (0, no session yet), machine IDLE —
            // the window and its collector launch legitimately.
            h.manager.maybeOpenFollowUpWindow(spoke = true, id = 0, forceOpen = true)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW) delay(20)
            }

            // Close the window. NOTE: setFollowUpWindow(false) does NOT bump
            // the seq — it exercises the onset re-check's STATE half. The
            // collector's cancellation is ASYNC, so it may still be draining.
            h.manager.setFollowUpWindow(enabled = false, windowMs = 5_000)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }

            // Loud frames immediately after the close: a surviving ghost
            // collector (pre-fix) onsets and fires startSession(fromFollowUp
            // = true) FROM IDLE — a wake-word-free session the user never
            // asked for. Post-fix the onset re-check drops it.
            source.speech = true
            delay(1_000) // several lead-in (200 ms) + onset windows

            assertEquals(
                "ghost follow-up turn started after the window closed",
                0,
                h.asr.streams.size,
            )
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `barge-in racing the window open must not spawn a ghost follow-up turn`() = runBlocking {
        val source = ToggleableSpeechSource().apply { speech = true }
        val h = VadHarness(source)
        try {
            h.pipeline.start() // LOUD frames flow — onset within ~200 ms
            // White-box open with the CURRENT seq: the window + collector are
            // legitimate at this instant.
            h.manager.maybeOpenFollowUpWindow(spoke = true, id = 0, forceOpen = true)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.FOLLOW_UP_WINDOW) delay(20)
            }

            // Barge-in: bumps the seq (0 → 1) and — under the same monitor —
            // cancels the windowJob that parents the collector; the collector's
            // own teardown coroutines then run. The exact interleaving the
            // in-lock seq gate and the onset re-check exist for.
            h.manager.startSession()
            withTimeout(5_000) { while (h.asr.streams.isEmpty()) delay(20) }

            // A ghost onset here would start a SECOND (follow-up) session
            // over the live ASR utterance of the barge-in session.
            delay(1_500) // several lead-in + onset windows
            assertEquals(
                "ghost follow-up session spawned over the live ASR",
                1,
                h.asr.streams.size,
            )
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
            // NOTE: followUpProgress is deliberately NOT asserted here —
            // startSession tears the windowJob down but never resets the orb
            // (a pre-existing cosmetic gap owned by the window lifecycle, not
            // the F2 seq re-check).
        } finally {
            h.shutdown()
        }
    }
}

// ---------------------------------------------------------------------------
// P1-S (audit 2026-09-16): event provenance + guarded terminal effects
// ---------------------------------------------------------------------------

/**
 * Player that counts flush() calls — the observable for fix #2: a stale
 * session's late failure must NOT flush the LIVE session's playback.
 * Completes nothing (like [GatedPlayer]): a turn parked in the LLM is what
 * these tests want, and flush-counting needs no real audio.
 */
class FlushCountingPlayer : TtsPlayer {
    val flushes = java.util.concurrent.atomic.AtomicInteger(0)

    override fun play(pcm: Flow<ByteArray>): Deferred<Unit> =
        kotlinx.coroutines.CompletableDeferred<Unit>()

    override fun flush() {
        flushes.incrementAndGet()
    }

    override fun release() {}
}

/**
 * White-box regressions for locked decision 1 (REMEDIATION/audit P1-S #1):
 * every TurnRunner → SessionManager event carries the SEQ OF THE TURN that
 * emitted it, and the manager validates it at apply time. Before the fix the
 * guard read the manager's OWN current seq, so it was a tautology and any
 * straggler event from a superseded turn moved the shared machine.
 *
 * [SessionManager.applyTurnEvent] is the exact seam production uses (the
 * turnRunner wiring is a one-liner over it). A straggler cannot be forced
 * end-to-end — a superseded turn's coroutine is cancelled and stops emitting —
 * so these tests inject through the seam, which is the only honest way to
 * prove the guard fires (precedent: maybeOpenFollowUpWindow's white-box
 * seq-guard tests).
 *
 * The same provenance rule covers the two SHARED UI STATE wires
 * ([SessionManager.publishPartial] / [SessionManager.publishActivity]): a
 * draining turn may not rewrite — or blank — what the live session shows.
 */
class SessionEventProvenanceTest {

    private suspend fun awaitState(h: Harness, expected: AssistantState) {
        withTimeout(5_000) {
            while (h.stateMachine.currentState() != expected) delay(10)
        }
    }

    /** Open a session and wait until its ASR stream exists (index [streamIndex]). */
    private suspend fun startSessionAndWaitStream(h: Harness, streamIndex: Int): FakeAsrStream {
        h.manager.startSession()
        withTimeout(5_000) {
            while (h.asr.streams.size <= streamIndex) delay(20)
        }
        return h.asr.streams[streamIndex].also { it.awaitEvents() }
    }

    @Test
    fun `stale turn events are dropped and never move the live machine`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf()))
        try {
            // Two sessions: turn 1 is superseded by turn 2 (LISTENING), whose
            // live partial transcript is the canary for "did the machine lane
            // belong to me".
            startSessionAndWaitStream(h, 0)
            val live = startSessionAndWaitStream(h, 1)
            awaitState(h, AssistantState.LISTENING)
            live.emitPartial("живой текст")
            withTimeout(5_000) {
                while (h.manager.partialTranscript.value != "живой текст") delay(10)
            }

            // --- every one of these WOULD have applied before P1-S #1 ------
            // LISTENING → THINKING
            assertFalse(
                "a superseded turn's SpeechCaptured must not be applied",
                h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.SpeechCaptured)),
            )
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
            // LISTENING → IDLE (would also end the live session's window)
            assertFalse(
                "a superseded turn's NoSpeech must not be applied",
                h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.NoSpeech)),
            )
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
            assertEquals("живой текст", h.manager.partialTranscript.value)

            // --- positive controls: the SAME events with the live turn's id -
            assertTrue(h.manager.applyTurnEvent(TurnEvent(2, SessionEvent.SpeechCaptured)))
            awaitState(h, AssistantState.THINKING)
            assertFalse(h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.PlaybackStarted)))
            assertEquals(AssistantState.THINKING, h.stateMachine.currentState())
            assertTrue(h.manager.applyTurnEvent(TurnEvent(2, SessionEvent.PlaybackStarted)))
            awaitState(h, AssistantState.SPEAKING)
            assertFalse(h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.LlmStarted)))
            assertEquals(AssistantState.SPEAKING, h.stateMachine.currentState())
            assertTrue(h.manager.applyTurnEvent(TurnEvent(2, SessionEvent.LlmStarted)))
            awaitState(h, AssistantState.THINKING)
            // The live partial is the manager's own state; dropped events and
            // machine transitions must not touch it.
            assertEquals("живой текст", h.manager.partialTranscript.value)
        } finally {
            h.shutdown()
        }
    }

    /**
     * P1-S #2: reportFailure's side effects (player flush, transcript and
     * activity clears, error voice) belong to the session that OWNS the
     * machine. A late failure from a superseded turn must leave a live
     * THINKING turn's playback, status label and state alone.
     */
    @Test
    fun `stale failure leaves the live session's playback transcript and label alone`() = runBlocking {
        val player = FlushCountingPlayer()
        val h = Harness(
            ScriptedLlm(mutableListOf()),
            llmOverride = HangingLlm(), // park both turns in THINKING
            playerOverride = player,
        )
        try {
            var errors = 0
            h.manager.setOnError { errors++ }

            // Turn 1 reaches THINKING, then the user barges in: turn 2 owns the
            // machine (seq 2) and is THINKING with a live activity label.
            startSessionAndWaitStream(h, 0).emitFinal("первый запрос")
            awaitState(h, AssistantState.THINKING)
            val live = startSessionAndWaitStream(h, 1)
            live.emitFinal("второй запрос")
            awaitState(h, AssistantState.THINKING)
            withTimeout(5_000) {
                while (h.manager.turnActivity.value == null) delay(10)
            }
            val flushesAtStaleCall = player.flushes.get()

            h.manager.reportFailure(1, "поздний сбой") // stale id → fully dropped

            assertEquals(
                "a stale failure must not flush the live session's playback",
                flushesAtStaleCall,
                player.flushes.get(),
            )
            assertEquals(AssistantState.THINKING, h.stateMachine.currentState())
            assertNotNull("a stale failure must not wipe the live activity label", h.manager.turnActivity.value)
            assertEquals("a stale failure must not speak the error voice", 0, errors)

            // Positive control: the SAME call with the live id is the machine's
            // terminal — flush + clears + IDLE + error voice.
            h.manager.reportFailure(2, "сбой живого сеанса")
            assertEquals(flushesAtStaleCall + 1, player.flushes.get())
            assertEquals(AssistantState.IDLE, h.stateMachine.currentState())
            assertNull(h.manager.turnActivity.value)
            assertEquals("", h.manager.partialTranscript.value)
            assertEquals(1, errors)
        } finally {
            h.shutdown()
        }
    }

    /**
     * P1-S #1 must not regress FIXPLAN B's voice-stop contract: «стоп» in
     * THINKING cancels the turn, goes IDLE, keeps the wake collector alive —
     * and the superseded turn's later events are still provably inert.
     */
    @Test
    fun `voice stop keeps its contract with the seq-stamped event shape`() = runBlocking {
        val player = FlushCountingPlayer()
        val h = Harness(
            ScriptedLlm(mutableListOf()),
            llmOverride = HangingLlm(),
            playerOverride = player,
        )
        try {
            h.manager.startListening()
            h.wake.awaitSubscribed()
            val first = startSessionAndWaitStream(h, 0)
            first.emitFinal("долгий запрос")
            awaitState(h, AssistantState.THINKING)
            withTimeout(5_000) { while (h.manager.turnActivity.value == null) delay(10) }
            val flushesBeforeStop = player.flushes.get()

            h.wake.detections.emit(Detection.StopPhrase("стоп"))
            awaitState(h, AssistantState.IDLE)
            assertTrue(
                "stop must flush the queued speech",
                player.flushes.get() > flushesBeforeStop,
            )
            assertNull(h.manager.turnActivity.value)
            assertTrue(
                "voice stop must NOT disarm the wake collector (that is cancelAll) — " +
                    "subscriptions=" + h.wake.detections.subscriptionCount.value,
                h.wake.detections.subscriptionCount.value > 0,
            )

            // A NEW live session — seq 3, not 2: startSession made 1,
            // stopActiveTurn's contract seq bump made 2, and the fresh
            // startSession bumps AGAIN (supersede-first is unconditional).
            val live = startSessionAndWaitStream(h, 1)
            awaitState(h, AssistantState.LISTENING)
            live.emitPartial("живой текст")
            withTimeout(5_000) {
                while (h.manager.partialTranscript.value != "живой текст") delay(10)
            }
            // Turn 1 is dead: its events stay inert even if they arrive now.
            assertFalse(h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.SpeechCaptured)))
            assertFalse(h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.NoSpeech)))
            assertFalse(h.manager.applyTurnEvent(TurnEvent(1, SessionEvent.PlaybackStarted)))
            // The stop bump's own seq (2) names no turn — equally inert.
            assertFalse(h.manager.applyTurnEvent(TurnEvent(2, SessionEvent.SpeechCaptured)))
            assertEquals(AssistantState.LISTENING, h.stateMachine.currentState())
            assertEquals("живой текст", h.manager.partialTranscript.value)
            assertTrue(h.manager.applyTurnEvent(TurnEvent(3, SessionEvent.SpeechCaptured)))
            awaitState(h, AssistantState.THINKING)
        } finally {
            h.shutdown()
        }
    }

    /**
     * P1-S #4 (+8): the matched keyword is USER SPEECH CONTENT, so it may be
     * logged at DEBUG only — FileLoggingTree persists INFO+ to disk in release
     * (AGENTS.md). Pins the level, not just the current call site.
     */
    @Test
    fun `stop-phrase keyword is logged at DEBUG never at INFO or above`() = runBlocking {
        val captured = CopyOnWriteArrayList<Pair<Int, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                captured.add(priority to message)
            }
        }
        Timber.plant(tree)
        try {
            val h = Harness(ScriptedLlm(mutableListOf()))
            try {
                h.manager.startListening()
                h.wake.awaitSubscribed()
                // IDLE/LISTENING: the gate passes the detection, the router
                // ignores it — the one path that fires the keyword log without
                // a live turn.
                h.wake.detections.emit(Detection.StopPhrase("стоп"))
                withTimeout(5_000) {
                    while (captured.none { it.second.contains("стоп") }) delay(20)
                }
                val contentBearing = captured.filter { it.second.contains("стоп") }
                assertTrue(
                    "keyword content leaked into INFO+ (persisted to disk): " +
                        contentBearing.filter { it.first >= PRI_INFO }.map { it.second },
                    contentBearing.all { it.first <= PRI_DEBUG },
                )
            } finally {
                h.shutdown()
            }
        } finally {
            Timber.uproot(tree)
        }
    }

    /**
     * P1-S #1 EXTENDED (accepted decision): [SessionManager.publishPartial] and
     * [SessionManager.publishActivity] used to be handed to TurnRunner UNGATED,
     * so a draining turn could blank the live partial transcript (its final `""`
     * is the worst case) or set/clear the live session's activity pill. Both
     * take the turn id now — stale must be a COMPLETE no-op (return false, state
     * untouched), and the live turn must still be able to write and clear.
     */
    @Test
    fun `a superseded turn cannot rewrite or blank the live partial and pill`() = runBlocking {
        val h = Harness(ScriptedLlm(mutableListOf()), llmOverride = HangingLlm())
        try {
            // Turn 2 owns the machine (LISTENING) with a live partial; turn 1 is
            // superseded and dead.
            startSessionAndWaitStream(h, 0)
            val live = startSessionAndWaitStream(h, 1)
            awaitState(h, AssistantState.LISTENING)
            live.emitPartial("живой текст")
            withTimeout(5_000) {
                while (h.manager.partialTranscript.value != "живой текст") delay(10)
            }

            assertFalse(
                "a stale turn's partial must not land",
                h.manager.publishPartial(1, "чужой текст"),
            )
            assertFalse(
                "a stale turn's CLEARING partial must not blank the live one",
                h.manager.publishPartial(1, ""),
            )
            assertEquals("живой текст", h.manager.partialTranscript.value)
            assertFalse(
                "a stale turn must not label the live session",
                h.manager.publishActivity(1, TurnActivity.Thinking),
            )
            assertNull(h.manager.turnActivity.value)

            // Positive controls: the identical calls with the LIVE id land.
            assertTrue(h.manager.publishPartial(2, "живой текст 2"))
            assertEquals("живой текст 2", h.manager.partialTranscript.value)
            assertTrue(h.manager.publishActivity(2, TurnActivity.Thinking))
            assertEquals(TurnActivity.Thinking, h.manager.turnActivity.value)
            assertTrue(h.manager.publishActivity(2, TurnActivity.ToolRunning("setAlarm")))
            assertEquals(TurnActivity.ToolRunning("setAlarm"), h.manager.turnActivity.value)
            assertTrue("the live turn must still be able to clear its own pill", h.manager.publishActivity(2, null))
            assertNull(h.manager.turnActivity.value)

            // And a stale CLEAR of a pill the live session just set is dropped.
            assertTrue(h.manager.publishActivity(2, TurnActivity.Thinking))
            assertFalse(h.manager.publishActivity(1, null))
            assertEquals(TurnActivity.Thinking, h.manager.turnActivity.value)
        } finally {
            h.shutdown()
        }
    }

    private companion object {
        // android.util.Log priorities, inlined (unit tests stub the Log class).
        const val PRI_DEBUG = 3
        const val PRI_INFO = 4
    }
}
