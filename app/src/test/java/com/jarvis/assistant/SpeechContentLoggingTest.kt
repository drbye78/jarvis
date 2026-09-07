package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.speech.tts.TtsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

// ---------------------------------------------------------------------------
// REMEDIATION_PLAN P0.1–P0.4: speech content must be unreachable at INFO+.
//
// FileLoggingTree is planted in EVERY build at INFO+ and persists 3×512 KB
// rotating files — anything Timber.i/w/e writes ends up on disk in release.
// The turn hot path (ASR final transcript, TTS sentences) therefore logs
// content only at DEBUG and keeps a content-free summary at INFO+. This test
// drives full turns through SessionManager (the same fakes as
// SessionManagerTest) with sentinel utterances and asserts that no captured
// entry at INFO or above contains either sentinel. AGENTS.md: "Never log fact
// content outside DEBUG".
// ---------------------------------------------------------------------------

/** Sentinel user utterance fed through ASR. */
private const val UTTERANCE_SENTINEL = "СЕКРЕТНАЯ_ФРАЗА_ТЕСТ_АУДИТ_9381"

/** Sentinel assistant reply streamed by the fake LLM ('!' always ends a sentence). */
private const val REPLY_SENTINEL = "СЕКРЕТНЫЙ_ОТВЕТ_7734!"

// android.util.Log priority values, kept local so this JVM test never touches
// the Android stub jar (constants are inlined by the compiler, but locality
// keeps the assertion readable).
private const val PRI_DEBUG = 3
private const val PRI_INFO = 4

/** Timber tree that records every log (priority + message) for assertions. */
private class CapturingTree : Timber.Tree() {
    data class Entry(val priority: Int, val message: String)

    val entries = CopyOnWriteArrayList<Entry>()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        entries.add(Entry(priority, message))
    }
}

/** TTS client whose synthesis stream fails immediately on collection. */
class FailingTtsClient : TtsClient {
    val asked = CopyOnWriteArrayList<String>()

    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = flow {
        asked.add(text)
        error("tts synthesis boom")
    }
}

/**
 * Minimal SessionManager wiring — mirrors the (file-private) Harness in
 * SessionManagerTest.kt, reusing its public top-level fakes.
 */
private class SpeechLogHarness(
    llm: LlmClient,
    tts: TtsClient,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val dao = FakeMessageDao()
    val conversation = ConversationManager(dao, maxMessages = 20)
    val pipeline = AudioPipeline(scope, PumpAudioSource())
    val stateMachine = SessionStateMachine()
    val asr = FakeAsrClient()
    val wake = FakeWakeWord()
    val online = FakeOnline()

    val manager = SessionManager(
        audioPipeline = pipeline,
        wakeWordDetector = wake,
        asrClient = asr,
        llm = llm,
        ttsClient = tts,
        player = FakePlayer(),
        functionRouter = FakeTools(),
        conversationManager = conversation,
        stateMachine = stateMachine,
        networkMonitor = online,
        config = JarvisConfig(
            maxUtteranceMs = Long.MAX_VALUE,
            ttsSentenceTimeoutMs = 5_000,
            ttsDrainTimeoutMs = 5_000,
            llmTimeoutMs = 10_000,
        ),
        scope = scope,
    )

    val tts: TtsClient = tts

    fun shutdown() {
        manager.cancelAll()
        pipeline.release()
        scope.cancel()
    }

    /** Emits a wake word, waits for the ASR stream, delivers the transcript. */
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

class SpeechContentLoggingTest {

    private lateinit var tree: CapturingTree

    @Before
    fun plantCapturingTree() {
        Timber.uprootAll()
        tree = CapturingTree()
        Timber.plant(tree)
    }

    @After
    fun uprootCapturingTree() {
        Timber.uprootAll()
    }

    /** INFO+ entries containing either sentinel = a release-log content leak. */
    private fun CapturingTree.leaks() = entries
        .filter { it.priority >= PRI_INFO }
        .filter { it.message.contains(UTTERANCE_SENTINEL) || it.message.contains(REPLY_SENTINEL) }

    @Test
    fun `clean turn never logs speech content at INFO or above`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(listOf(LlmChunk.Text(REPLY_SENTINEL), LlmChunk.Done))
        )
        val h = SpeechLogHarness(llm, FakeTtsClient())
        try {
            h.runTurn(UTTERANCE_SENTINEL)
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }

            // Positive controls: the turn really carried BOTH sentinels.
            assertTrue(
                "utterance never reached history — the test would pass vacuously",
                h.dao.rows.any { it.role == "user" && it.content.contains(UTTERANCE_SENTINEL) },
            )
            assertTrue(
                "reply never reached TTS — test would pass vacuously",
                (h.tts as FakeTtsClient).spoken.any { it.contains(REPLY_SENTINEL) },
            )

            assertTrue(
                "speech content leaked at INFO+: ${tree.leaks().map { it.message }}",
                tree.leaks().isEmpty(),
            )
            // The content still exists at DEBUG — this is a downgrade, not a
            // deletion (the ASR-final DEBUG line is the pinned carrier here).
            assertTrue(
                "no DEBUG entry carries the utterance — the capture is broken",
                tree.entries.any { it.priority == PRI_DEBUG && it.message.contains(UTTERANCE_SENTINEL) },
            )
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `tts sentence failure logs length only - no content at INFO or above`() = runBlocking {
        val llm = ScriptedLlm(
            mutableListOf(listOf(LlmChunk.Text(REPLY_SENTINEL), LlmChunk.Done))
        )
        val h = SpeechLogHarness(llm, FailingTtsClient())
        try {
            h.runTurn(UTTERANCE_SENTINEL)
            // The sentence is dropped, the turn still drains back to IDLE.
            withTimeout(5_000) {
                while (h.stateMachine.currentState() != AssistantState.IDLE) delay(20)
            }

            // Positive control: the TTS-failure ERROR path really fired.
            assertTrue(
                "expected the TTS-failure entry — test would pass vacuously",
                tree.entries.any { it.priority >= PRI_INFO && it.message.contains("TTS sentence failed") },
            )
            assertTrue(
                "sentinel never reached TTS — test would pass vacuously",
                (h.tts as FailingTtsClient).asked.any { it.contains(REPLY_SENTINEL) },
            )

            assertTrue(
                "speech content leaked at INFO+: ${tree.leaks().map { it.message }}",
                tree.leaks().isEmpty(),
            )
            // The dropped sentence's content survives only at DEBUG.
            assertTrue(
                "no DEBUG entry carries the dropped sentence",
                tree.entries.any { it.priority == PRI_DEBUG && it.message.contains(REPLY_SENTINEL) },
            )
        } finally {
            h.shutdown()
        }
    }
}
