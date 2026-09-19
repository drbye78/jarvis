package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.cognitive.CognitiveDeps
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.extract.FakeCommandEventDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeHabitRuleDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.media.MediaAppInfo
import com.jarvis.assistant.media.MediaControllerHandle
import com.jarvis.assistant.media.MediaGateway
import com.jarvis.assistant.media.MusicAppCatalog
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.session.SessionManager
import com.jarvis.assistant.session.SessionStateMachine
import com.jarvis.assistant.speech.tts.TtsClient
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
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

/** Sentinel music request driven through the orchestrator's not-verified lane. */
private const val MUSIC_QUERY_SENTINEL = "СЕКРЕТНЫЙ_ЗАПРОС_МУЗЫКИ_9381"

/** Sentinel now-playing metadata (echoes what the user asked to play). */
private const val MEDIA_TITLE_SENTINEL = "СЕКРЕТНЫЙ_ТРЕК_9381"
private const val MEDIA_ARTIST_SENTINEL = "СЕКРЕТНЫЙ_ИСПОЛНИТЕЛЬ_9381"

/**
 * Lowercase and punctuation-free so `ArgFingerprints.normalize` leaves it
 * intact inside the resulting `q:` fingerprint.
 */
private const val HABIT_SENTINEL = "секретнаяпривычка9381"

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

/**
 * Media fake for the logging lane: one live session that never honors
 * playFromSearch — the orchestrator then reaches its not-verified query log
 * AND dumps the session's now-playing capability line.
 */
private class LogFakeHandle(
    override val packageName: String,
    var np: NowPlaying = NowPlaying(),
) : MediaControllerHandle {
    override fun snapshot(): NowPlaying = np
    override fun playFromSearch(query: String): Boolean = true
    override fun play(): Boolean = true
    override fun pause(): Boolean = true
    override fun skipToNext(): Boolean = true
    override fun skipToPrevious(): Boolean = true
    override fun stop(): Boolean = true
}

private class LogFakeMediaGateway(
    private val handles: List<MediaControllerHandle>,
) : MediaGateway {
    override fun hasNotificationListenerAccess(): Boolean = true
    override fun activeControllers(): List<MediaControllerHandle> = handles
    override fun dispatchMediaKey(keyCode: Int) = Unit
    override fun openAppSearch(app: MediaAppInfo, query: String): Boolean = true
    override fun launchApp(app: MediaAppInfo): Boolean = false
}

/** Tiny-budget orchestrator so verify polls complete instantly on the test clock. */
private fun logOrchestrator(gateway: MediaGateway) = MusicPlaybackOrchestrator(
    gateway,
    MusicAppCatalog({ listOf("ru.yandex.music" to "Яндекс Музыка") }),
    budgets = MusicPlaybackOrchestrator.Budgets(
        verifyPollMs = 10,
        verifyTotalMs = 50,
        coldStartPollMs = 10,
        coldStartTotalMs = 100,
    ),
)

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

    // ------------------------------------------------------------------
    // Live-violation pins (audit): three non-session lanes that used to
    // lift user content to WARN/INFO — and the single-quoted-span blind
    // spot that let one of them sail past LogScrubber.
    // ------------------------------------------------------------------

    /** INFO+-entries containing [sentinel] = a release-log content leak. */
    private fun CapturingTree.leaks(sentinel: String) = entries
        .filter { it.priority >= PRI_INFO && it.message.contains(sentinel) }

    @Test
    fun `unverified music query is logged at DEBUG never at INFO or above`() = runTest {
        val handle = LogFakeHandle(
            "ru.yandex.music",
            np = NowPlaying(
                title = "Старая",
                state = NowPlaying.STATE_PLAYING,
                positionMs = 60_000,
            ),
        )
        val gw = LogFakeMediaGateway(listOf(handle))

        val out = logOrchestrator(gw).playSearchQuery(MUSIC_QUERY_SENTINEL, null)

        // Positive control: the cascade really reached the not-verified branch.
        assertEquals(MusicPlaybackOrchestrator.Status.SEARCH_OPENED, out.status)
        assertTrue(
            "music query leaked at INFO+: ${tree.leaks(MUSIC_QUERY_SENTINEL).map { it.message }}",
            tree.leaks(MUSIC_QUERY_SENTINEL).isEmpty(),
        )
        // The content survives at DEBUG — this is a downgrade, not a deletion.
        assertTrue(
            "the query never reached the DEBUG lane — the test would pass vacuously",
            tree.entries.any { it.priority == PRI_DEBUG && it.message.contains(MUSIC_QUERY_SENTINEL) },
        )
        // The content-free WARN summary still exists.
        assertTrue(
            "expected the content-free not-verified WARN",
            tree.entries.any {
                it.priority >= PRI_INFO && it.message.contains("playFromSearch not verified")
            },
        )
    }

    @Test
    fun `media diagnostics capability line omits now-playing content at INFO`() = runTest {
        val handle = LogFakeHandle(
            "ru.yandex.music",
            np = NowPlaying(
                title = MEDIA_TITLE_SENTINEL,
                artist = MEDIA_ARTIST_SENTINEL,
                state = NowPlaying.STATE_PLAYING,
                positionMs = 0,
            ),
        )
        val gw = LogFakeMediaGateway(listOf(handle))

        logOrchestrator(gw).playSearchQuery("любой запрос", null)

        val titleLeaks = tree.leaks(MEDIA_TITLE_SENTINEL)
        val artistLeaks = tree.leaks(MEDIA_ARTIST_SENTINEL)
        assertTrue(
            "now-playing title leaked at INFO+: ${titleLeaks.map { it.message }}",
            titleLeaks.isEmpty(),
        )
        assertTrue(
            "now-playing artist leaked at INFO+: ${artistLeaks.map { it.message }}",
            artistLeaks.isEmpty(),
        )
        // The track is still named at DEBUG (the DEBUG-only companion line).
        assertTrue(
            "no DEBUG entry carries the now-playing title — the capture is broken",
            tree.entries.any { it.priority == PRI_DEBUG && it.message.contains(MEDIA_TITLE_SENTINEL) },
        )
    }

    @Test
    fun `habit accept fingerprint is logged at DEBUG never at INFO or above`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = 1_000_000L
        rules.rows[1L] = HabitRuleEntity(
            id = 1L,
            kind = HabitRuleEntity.KIND_TIME_WINDOW,
            tool = "playMusic",
            argsFingerprint = "q:$HABIT_SENTINEL",
            hourBucket = 10,
            daySet = null,
            supportCount = 9,
            state = HabitRuleEntity.STATE_PROBATION,
            acceptCount = 0,
            rejectCount = 0,
            lastSuggestedAt = null,
            lastFiredAt = now - 1_000L, // inside ACCEPT_WINDOW_MS
            mutedUntil = null,
            createdAt = 0L,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = CognitiveCoordinator(
            deps = CognitiveDeps(
                factDao = FakeUserFactDao(),
                queueDao = FakeExtractionQueueDao(),
                metaDao = FakeMemoryMetaDao(),
                messageDao = FakeMessageDao(),
                llm = object : LlmClient {
                    override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                        throw AssertionError("behaviour telemetry must never call the LLM")
                },
                memoryEnabled = MutableStateFlow(true),
                autoExtractEnabled = MutableStateFlow(false),
                cloudEnabled = MutableStateFlow(false),
                sensitiveVisible = MutableStateFlow(true),
                eventDao = events,
                ruleDao = rules,
                strings = ToolStrings.Default,
                nowMs = { now },
            ),
            parentScope = scope,
        )
        try {
            coordinator.recordCommandEvent(
                tool = "playMusic",
                argsJson = """{"query":"$HABIT_SENTINEL"}""",
                ok = true,
                latencyMs = 5L,
            )
        } finally {
            scope.cancel()
        }

        // Positive control: the accept path really fired (count incremented).
        assertEquals(1, rules.rows[1L]!!.acceptCount)
        assertTrue(
            "habit fingerprint leaked at INFO+: ${tree.leaks(HABIT_SENTINEL).map { it.message }}",
            tree.leaks(HABIT_SENTINEL).isEmpty(),
        )
        // The fingerprint survives at DEBUG — this is a downgrade, not a deletion.
        assertTrue(
            "the fingerprint never reached the DEBUG lane — the test would pass vacuously",
            tree.entries.any { it.priority == PRI_DEBUG && it.message.contains(HABIT_SENTINEL) },
        )
    }
}
