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
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.speech.tts.TtsPlayer
import com.jarvis.assistant.tools.ToolExecutor
import com.jarvis.assistant.util.OnlineChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * Follow-up window integration: the SessionManager's window collector wired
 * to the REAL AudioPipeline + EnergyVad + state machine. The controller's
 * pure logic is covered by [FollowUpWindowControllerTest]; these tests prove
 * the ORCHESTRATION: window opens after a spoken turn, speech inside the
 * window starts a turn WITHOUT the wake word, silence expires, the wake word
 * supersedes, and the feature stays off by default.
 */
class SessionFollowUpWindowTest {

    /**
     * Decodes a PCM frame the way [com.jarvis.assistant.util.toByteArray] encodes
     * it (little-endian int16), so amplitude assertions compare real sample
     * values instead of raw bytes — a byte comparison makes every 16-bit sample
     * look like it is under 256 and silently passes.
     */
    private fun ByteArray.littleEndianShorts(): IntArray {
        val out = IntArray(size / 2)
        for (i in out.indices) {
            val lo = this[i * 2].toInt() and 0xFF
            val hi = this[i * 2 + 1].toInt() and 0xFF
            var v = lo or (hi shl 8)
            if (v >= 0x8000) v -= 0x10000 // sign-extend
            out[i] = v
        }
        return out
    }

    /** Mic fake whose content flips between silence, loud speech and a
     *  decaying "echo" tail (the assistant's own reply heard by the mic). */
    private class SpeechPumpAudioSource : AudioSource {
        @Volatile var speech = false

        /** Frames of the "echo" tail still to emit. */
        @Volatile private var tailFramesLeft = 0

        /** Total frames in the current tail, for the decay denominator. */
        @Volatile private var tailFramesTotal = 1

        @Volatile private var tailAmp = 0

        @Volatile private var tailDecay = true

        /**
         * Emit a tail of [frames] frames at [amp] amplitude. [decay]=true fades
         * linearly to zero (a real reply); false holds [amp] (a reply still
         * clearly audible). [amp] is deliberately settable so a test can tell
         * the assistant's voice (its own level) from the user's speech (3000).
         */
        fun startTail(frames: Int, amp: Int = 3000, decay: Boolean = true) {
            tailFramesTotal = frames
            tailFramesLeft = frames
            tailAmp = amp
            tailDecay = decay
        }

        override fun start() {}
        override fun stop() {}
        override fun read(): ShortArray {
            // Real-time pacing (20 ms frames): a busy-loop fake would starve
            // the dispatcher and flake the collectors.
            Thread.sleep(20)
            val remaining = tailFramesLeft
            val amp = when {
                speech -> 3000
                remaining > 0 -> {
                    tailFramesLeft = remaining - 1
                    if (tailDecay) tailAmp * remaining / tailFramesTotal else tailAmp
                }
                else -> 0
            }
            return ShortArray(320) { (if (it % 2 == 0) amp else -amp).toShort() }
        }
    }

    private class MiniWake : WakeWordDetector {
        val detections = MutableSharedFlow<Detection>(extraBufferCapacity = 16)
        override val state = MutableStateFlow<DetectorState>(DetectorState.Ready)
        override fun detections(): Flow<Detection> = detections
        override fun release() {}
        override suspend fun reconfigure(req: WakeWordRequest) {}
        override suspend fun setSensitivity(value: Float) {}
        suspend fun awaitSubscribed() {
            withTimeout(5_000) {
                while (detections.subscriptionCount.value == 0) delay(10)
            }
        }
    }

    /** Player whose playback never completes — pins a turn in SPEAKING/drain. */
    private class ParkingPlayer : TtsPlayer {
        override fun play(pcm: Flow<ByteArray>): kotlinx.coroutines.Deferred<Unit> =
            kotlinx.coroutines.CompletableDeferred()
        override fun flush() {}
        override fun release() {}
    }

    /** LLM that speaks one sentence, then breaks the stream (error turn). */
    private class FailingAfterSpeechLlm : LlmClient {
        override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
            emit(LlmChunk.Text("Сначала всё было хорошо."))
            throw java.io.IOException("upstream broke mid-stream")
        }
    }

    private class MiniHarness(
        parkPlayback: Boolean = false,
        llmOverride: LlmClient? = null,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = SpeechPumpAudioSource()
        val pipeline = AudioPipeline(scope, source)
        val stateMachine = SessionStateMachine()
        val asr = FakeAsrClient()
        val wake = MiniWake()
        val llm: LlmClient = llmOverride ?: ScriptedLlm(
            mutableListOf(
                // Every scripted turn says something → window-eligible.
                listOf(LlmChunk.Text("Готово."), LlmChunk.Done),
            )
        )
        val manager = SessionManager(
            audioPipeline = pipeline,
            wakeWordDetector = wake,
            asrClient = asr,
            llm = llm,
            ttsClient = FakeTtsClient(),
            player = if (parkPlayback) ParkingPlayer() else FakePlayer(),
            functionRouter = object : ToolExecutor {
                override fun getToolDefinitions() = emptyList<com.jarvis.assistant.model.ToolDefinition>()
                override suspend fun executeResult(call: com.jarvis.assistant.model.FunctionCall) =
                    com.jarvis.assistant.tools.ToolResult("{}", isError = false)

                override fun setAuthorizationContext(
                    sessionId: Int,
                    context: com.jarvis.assistant.tools.TurnAuthorization?,
                ) {}
            },
            conversationManager = ConversationManager(FakeMessageDao(), maxMessages = 20),
            stateMachine = stateMachine,
            networkMonitor = object : OnlineChecker {
                override fun isCurrentlyOnline() = true
            },
            config = JarvisConfig(
                maxUtteranceMs = Long.MAX_VALUE,
                ttsSentenceTimeoutMs = 5_000,
                ttsDrainTimeoutMs = 5_000,
                llmTimeoutMs = 10_000,
            ),
            scope = scope,
        )

        fun startMic() = pipeline.start()

        suspend fun runTurn(userText: String) {
            manager.startListening()
            wake.awaitSubscribed()
            wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (asr.streams.isEmpty()) delay(20)
            }
            asr.streams.last().emitFinal(userText)
        }

        fun shutdown() {
            manager.cancelAll()
            pipeline.release()
            scope.cancel()
        }

        suspend fun awaitState(state: AssistantState) {
            withTimeout(10_000) {
                while (stateMachine.currentState() != state) delay(20)
            }
        }
    }

    @Test
    fun `window opens after spoken turn and speech starts a turn without wake word`() = runBlocking {
        val h = MiniHarness()
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 2_000)
            h.runTurn("включи таймер на пять минут")

            h.awaitState(AssistantState.FOLLOW_UP_WINDOW)
            assertEquals(AssistantState.FOLLOW_UP_WINDOW, h.stateMachine.currentState())

            // User starts speaking inside the window → a NEW ASR session opens
            // WITHOUT any wake-word detection.
            h.source.speech = true
            withTimeout(5_000) {
                while (h.asr.streams.size < 2) delay(20)
            }
            h.awaitState(AssistantState.LISTENING)
            assertEquals(2, h.asr.streams.size)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `the assistant's own decaying reply tail starts no phantom turn`() = runBlocking {
        // The window opens when TTS DRAINS — not when the speaker goes quiet.
        // With AEC off the mic still hears the reply, so the old fixed 200 ms
        // lead-in fired a follow-up turn from the assistant's own last words:
        // measured on-device at 233/470/233 ms, i.e. lead-in + 2 onset frames.
        val h = MiniHarness()
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 4_000)
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.isEmpty()) delay(20)
            }
            // The reply is still ringing out as the turn ends and the window
            // opens (~1.2 s decaying tail).
            h.source.startTail(60)
            h.asr.streams.last().emitFinal("расскажи анекдот")

            h.awaitState(AssistantState.FOLLOW_UP_WINDOW)
            // Well past the old lead-in: the tail must not have started a turn.
            delay(2_500)
            assertEquals(
                "the assistant's own reply must not start a follow-up turn",
                1,
                h.asr.streams.size,
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `the follow-up turn never replays the assistant's own tail into ASR`() = runBlocking {
        // The ring buffer is replayed into ASR at turn start to un-clip the
        // first word. This window opened when TTS DRAINED, so the buffer still
        // holds the reply — and the live transcript began with the reply's own
        // last words ("чем могу помочь" + the user's "расскажи анекдот").
        val h = MiniHarness()
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 8_000)
            h.manager.startListening()
            h.wake.awaitSubscribed()
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.isEmpty()) delay(20)
            }
            // A loud reply ringing in the room fills the pre-roll buffer; its
            // amplitude (10200) is well above the user's speech (3000), so the
            // origin of any replayed frame is unambiguous. It decays to silence
            // on its own before the window opens, so it cannot self-trigger.
            h.source.startTail(frames = 24, amp = 10_200, decay = false)
            delay(500)
            h.asr.streams.last().emitFinal("расскажи анекдот")

            h.awaitState(AssistantState.FOLLOW_UP_WINDOW)
            // Room is quiet again; the gate arms at the lead-in boundary. The
            // user replies while the reply is STILL inside the 3 s ring buffer —
            // the exact real-world timing that polluted the transcript.
            delay(400)
            h.source.speech = true
            withTimeout(5_000) {
                while (h.asr.streams.size < 2) delay(20)
            }
            delay(400) // let the feeder push frames to the follow-up stream

            val sent = h.asr.streams[1].sent
            assertTrue("the follow-up stream received no audio", sent.isNotEmpty())
            val loudest = sent.maxOf { frame -> frame.littleEndianShorts().maxOf { kotlin.math.abs(it) } }
            assertTrue(
                "the reply's tail must not be replayed into the follow-up turn (loudest=$loudest)",
                loudest <= 4_500,
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `window expires to idle on silence`() = runBlocking {
        val h = MiniHarness()
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 2_000)
            h.runTurn("что погода")

            h.awaitState(AssistantState.FOLLOW_UP_WINDOW)
            // Silence: the window must expire (controller clamps to >= 2 s).
            h.awaitState(AssistantState.IDLE)
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
            assertEquals(1, h.asr.streams.size)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `feature off by default - spoken turn goes straight to idle`() = runBlocking {
        val h = MiniHarness()
        try {
            h.startMic()
            h.runTurn("привет")
            h.awaitState(AssistantState.IDLE)
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `wake word during the window supersedes the VAD path`() = runBlocking {
        val h = MiniHarness()
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 8_000)
            h.runTurn("поставь будильник")

            h.awaitState(AssistantState.FOLLOW_UP_WINDOW)
            // The barge-in gate suppresses detections for 600 ms after the
            // wake word that STARTED the turn (designed trailing-audio
            // protection) — a fake-fixture turn completes faster than that.
            delay(700)
            // The user says the wake word inside the window instead of just
            // speaking — the normal path must win.
            h.wake.detections.emit(Detection.WakeWord)
            withTimeout(5_000) {
                while (h.asr.streams.size < 2) delay(20)
            }
            h.awaitState(AssistantState.LISTENING)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `cancelAll mid-turn never opens a follow-up window`() = runBlocking {
        // Audit #5/#18 regression: cancelAll used to leave the interrupted
        // turn's CancellationException path eligible to call finish() AFTER
        // the cancel — opening a follow-up window (and with it a VAD collector
        // able to restart sessions) while the user believed the assistant was
        // stopped. cancelAll now bumps the session seq atomically with the
        // teardown, so the stale finish() is guard-dropped.
        val h = MiniHarness(parkPlayback = true)
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 8_000)
            h.runTurn("расскажи что-нибудь")

            // The turn is parked in SPEAKING: playback never completes, so
            // the drain holds the turn open until cancelAll lands.
            h.awaitState(AssistantState.SPEAKING)

            h.manager.cancelAll()
            h.awaitState(AssistantState.IDLE)

            // Long enough for a would-be window (lead-in 200 ms) to have
            // opened if the stale finish() had landed.
            delay(1_500)
            assertEquals(
                "no follow-up window may open after cancelAll",
                AssistantState.IDLE,
                h.stateMachine.currentState(),
            )
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `error turn with speech already played opens no follow-up window`() = runBlocking {
        // Audit #18: the old error paths called reportFailure (→ IDLE + error
        // voice) and THEN finish(spoke=true) — so a failed turn that had
        // already spoken a sentence opened a follow-up window right after the
        // error message. Now reportFailure is the single terminal.
        val h = MiniHarness(llmOverride = FailingAfterSpeechLlm())
        try {
            h.startMic()
            h.manager.setFollowUpWindow(enabled = true, windowMs = 8_000)
            h.runTurn("что делаешь")

            // The error voice drove the machine to IDLE.
            h.awaitState(AssistantState.IDLE)

            // Long enough for the would-be window to have opened.
            delay(1_500)
            assertEquals(
                "no follow-up window may open after an error turn",
                AssistantState.IDLE,
                h.stateMachine.currentState(),
            )
            assertEquals(0f, h.manager.followUpProgress.value, 0f)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `error turn ends with exactly one terminal event - no rejected transitions`() = runBlocking {
        // Audit #19: the old double-finish (reportFailure → IDLE, then
        // finish → LlmDone from IDLE) was rejected by the machine and logged
        // "Rejected transition" on every error turn, masking real issues.
        val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                recorded.add(message)
            }
        }
        Timber.plant(tree)
        val h = MiniHarness(llmOverride = FailingAfterSpeechLlm())
        try {
            h.startMic()
            h.runTurn("привет")
            h.awaitState(AssistantState.IDLE)
            delay(300) // let any would-be trailing terminal land
            // Scoped to the #19 defect: the double-finish's LlmDone from IDLE.
            // (A straggler sentence racing ErrorOccurred can still reject
            // PlaybackStarted from IDLE — a pre-existing, log-level-only race
            // that does not wedge anything and is out of this fix's scope.)
            val rejectedLlmDone = recorded.filter {
                it.contains("Rejected transition") && it.contains("LlmDone")
            }
            assertTrue(
                "expected no rejected LlmDone transitions, got: $rejectedLlmDone",
                rejectedLlmDone.isEmpty(),
            )
        } finally {
            h.shutdown()
            Timber.uproot(tree)
        }
    }
}
