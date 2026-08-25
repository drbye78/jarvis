package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordDetector
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.AsrStream
import com.jarvis.assistant.speech.asr.StreamingAsrClient
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecution
import com.jarvis.assistant.tools.ToolExecutor
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

// ---------------------------------------------------------------------------
// Fakes
// ---------------------------------------------------------------------------

class FakeAsrStream : AsrStream {
    // replay=1: the manager subscribes to events AFTER open() returns, so a
    // terminal event emitted in that window must be redelivered, mirroring a
    // real gRPC stream whose early messages are queued until consumption.
    private val _events = MutableSharedFlow<AsrEvent>(replay = 1, extraBufferCapacity = 32)
    override val events: SharedFlow<AsrEvent> = _events
    val sent = CopyOnWriteArrayList<ByteArray>()

    override fun send(pcm: ByteArray) {
        sent.add(pcm)
    }

    override fun finish() {}
    override fun cancel() {}

    fun emitFinal(text: String) {
        _events.tryEmit(AsrEvent.Final(text))
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
    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = flow {
        spoken.add(text)
        emit(ByteArray(1024))
    }
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

    // 'override' members are open by default, so CancellingTools can re-execute.
    override suspend fun execute(call: FunctionCall): ToolExecution {
        executed.add(call)
        return ToolExecution(call, result, isError = false)
    }
}

/** Tool whose execution suspends until the session is cancelled (barge-in). */
class CancellingTools : FakeTools() {
    override suspend fun execute(call: FunctionCall): ToolExecution {
        executed.add(call)
        awaitCancellation()
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
        wakeWordCooldownMs = 0,
        maxUtteranceMs = Long.MAX_VALUE,
        ttsSentenceTimeoutMs = 5_000,
        ttsDrainTimeoutMs = 5_000,
        llmTimeoutMs = 10_000,
    ),
    toolsOverride: FakeTools? = null,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = FakeMessageDao()
    val conversation = ConversationManager(dao, maxMessages = 20)
    val pipeline = AudioPipeline(scope, PumpAudioSource())
    val stateMachine = SessionStateMachine()
    val asr = FakeAsrClient()
    val wake = FakeWakeWord()
    val tts = FakeTtsClient()
    val player = FakePlayer()
    val tools: FakeTools = toolsOverride ?: FakeTools()
    val online = FakeOnline()

    val manager = SessionManager(
        audioPipeline = pipeline,
        wakeWordDetector = wake,
        asrClient = asr,
        llm = llm,
        ttsClient = tts,
        player = player,
        functionRouter = tools,
        conversationManager = conversation,
        stateMachine = stateMachine,
        networkMonitor = online,
        config = config,
        scope = scope,
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
            assertTrue(h.tts.spoken.any { it.contains("Привет") })
            assertTrue(h.tts.spoken.any { it.contains("Чем помочь") })
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
    fun `interrupted tool pass persists nothing`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(
                listOf( // pass 1: model calls the tool; the tool never finishes
                    LlmChunk.FunctionCallDelta(0, name = "setAlarm", argsDelta = "{}"),
                    LlmChunk.FunctionCallComplete(
                        ToolCall("call_1", function = FunctionCall("setAlarm", "{}"))
                    ),
                    LlmChunk.Done,
                )
            )
        )
        val tools = CancellingTools()
        val h = Harness(llm, toolsOverride = tools)
        try {
            h.runTurn("поставь будильник")

            withTimeout(5_000) {
                while (tools.executed.isEmpty()) delay(20)
            }

            // Barge-in: cancels the session while the tool is still executing.
            h.manager.startSession()
            // The new session opened its own ASR stream => the old turn was
            // already cancelled before it could persist anything.
            withTimeout(5_000) {
                while (h.asr.streams.size < 2) delay(20)
            }

            // NOTHING from the interrupted turn may reach the DB/history:
            // no assistant row with tool_calls, no partial tool rows.
            assertTrue(h.dao.rows.none { it.role == "assistant" && it.toolCallsJson != null })
            assertTrue(h.dao.rows.none { it.role == "tool" })
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
}
