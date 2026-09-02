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

/**
 * Follow-up window integration: the SessionManager's window collector wired
 * to the REAL AudioPipeline + EnergyVad + state machine. The controller's
 * pure logic is covered by [FollowUpWindowControllerTest]; these tests prove
 * the ORCHESTRATION: window opens after a spoken turn, speech inside the
 * window starts a turn WITHOUT the wake word, silence expires, the wake word
 * supersedes, and the feature stays off by default.
 */
class SessionFollowUpWindowTest {

    /** Mic fake whose content flips between silence and loud speech. */
    private class SpeechPumpAudioSource : AudioSource {
        @Volatile var speech = false
        override fun start() {}
        override fun stop() {}
        override fun read(): ShortArray {
            // Real-time pacing (20 ms frames): a busy-loop fake would starve
            // the dispatcher and flake the collectors.
            Thread.sleep(20)
            val amp = if (speech) 3000 else 0
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

    private class MiniHarness {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = SpeechPumpAudioSource()
        val pipeline = AudioPipeline(scope, source)
        val stateMachine = SessionStateMachine()
        val asr = FakeAsrClient()
        val wake = MiniWake()
        val llm = ScriptedLlm(
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
            player = FakePlayer(),
            functionRouter = object : ToolExecutor {
                override fun getToolDefinitions() = emptyList<com.jarvis.assistant.model.ToolDefinition>()
                override suspend fun execute(call: com.jarvis.assistant.model.FunctionCall) =
                    com.jarvis.assistant.tools.ToolExecution(call, "{}", isError = false)
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
}
