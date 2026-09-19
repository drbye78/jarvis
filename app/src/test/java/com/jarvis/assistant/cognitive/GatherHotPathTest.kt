package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.VectorMath
import com.jarvis.assistant.cognitive.extract.FakeEntityDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeFactVectorDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.recall.SearchTokenizer
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * F11 (REMEDIATION_PLAN): the gather read path does real CPU work — ranking
 * every active fact, RRF fusion, string rendering — and in production it is
 * invoked from the session's `Dispatchers.IO` scope. Two claims are pinned
 * here:
 *
 * 1. those phases are dispatched to the configured CPU dispatcher, never left
 *    on the caller's; and
 * 2. the read path degrades by PHASE — a spent budget drops the optional
 *    relation/vector boosts but still renders the mandatory lexical block,
 *    instead of letting the outer `withTimeout` discard all memory.
 */
class GatherHotPathTest {

    /** Delegates to [delegate] but counts every hop onto the CPU lane. */
    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val dispatches = AtomicInteger()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    private class CountingEngine(
        override val engineId: String,
        override val kind: EmbeddingEngine.Kind,
    ) : EmbeddingEngine {
        var embedCalls = 0
            private set

        override val dim = 2

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            embedCalls++
            return texts.map { floatArrayOf(0f, 1f) }
        }
    }

    private fun fact(id: String, value: String) = UserFactEntity(
        factId = id,
        category = FactCategory.OTHER.name,
        subject = "user",
        predicate = "likes",
        value = value,
        valueNormalized = value,
        searchText = SearchTokenizer.indexText("user", value, FactCategory.OTHER.name),
        confidence = 0.9f,
        origin = FactOrigin.EXPLICIT.name,
        status = FactStatus.ACTIVE.name,
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        // Fresh timestamps: the ranker applies recency decay against the real
        // clock, so an epoch-stamped row is decayed below the floor and never
        // reaches the render (same reason SemanticRecallTest uses `fresh`).
        createdAt = now,
        updatedAt = now,
        lastConfirmedAt = now,
        lastRecalledAt = null,
        recallCount = 0,
    )

    private val now = System.currentTimeMillis()

    private fun vec(factId: String, v: FloatArray) = FactVectorEntity(
        factId = factId,
        engineId = EmbeddingEngine.LOCAL_ID,
        dim = 2,
        vec = VectorMath.floatsToBytes(v),
        createdAt = 1L,
    )

    /**
     * @param elapsedNanos successive values of the monotonic clock; the last
     *   value repeats. `{ 0L }` = a normal (fast) pass.
     */
    private fun coordinator(
        factDao: FakeUserFactDao,
        vectorDao: FakeFactVectorDao = FakeFactVectorDao(),
        localEmbedder: EmbeddingEngine = CountingEngine(EmbeddingEngine.LOCAL_ID, EmbeddingEngine.Kind.LOCAL),
        cpuDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        elapsedNanos: () -> Long = { 0L },
    ): Pair<CognitiveCoordinator, CountingEngine> {
        val engine = localEmbedder as CountingEngine
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = CognitiveCoordinator(
            deps = CognitiveDeps(
                factDao = factDao,
                queueDao = FakeExtractionQueueDao(),
                metaDao = FakeMemoryMetaDao(),
                messageDao = FakeMessageDao(),
                llm = object : LlmClient {
                    override fun chatStream(request: ChatRequest) = flowOf(LlmChunk.Done)
                },
                memoryEnabled = MutableStateFlow(true),
                autoExtractEnabled = MutableStateFlow(false),
                cloudEnabled = MutableStateFlow(true),
                sensitiveVisible = MutableStateFlow(true),
                vectorDao = vectorDao,
                entityDao = FakeEntityDao(),
                embedderChoice = MutableStateFlow("LOCAL"),
                localEmbedder = engine,
                cpuDispatcher = cpuDispatcher,
                elapsedNow = elapsedNanos,
            ),
            parentScope = scope,
        )
        return coordinator to engine
    }

    @Test
    fun `the CPU phases are dispatched onto the configured lane`() = runTest {
        val factDao = FakeUserFactDao()
        factDao.insert(fact("a", "любит Тарковского"))
        // A real virtual-time dispatcher (NOT Unconfined) so the hop is
        // genuinely dispatched and counted, while still completing at the
        // current virtual instant — a thread-hopping delegate would let the
        // scheduler advance time and fire the 40 ms `withTimeout` before the
        // block returns (that race is a test artifact, not a product bug).
        val cpu = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val (c, _) = coordinator(factDao, cpuDispatcher = cpu)

        val block = c.gather("что я люблю")

        assertTrue(block.contains("любит Тарковского"))
        assertTrue(
            "the ranking phases must hop onto the configured CPU dispatcher",
            cpu.dispatches.get() > 0,
        )
    }

    @Test
    fun `a spent phase budget drops the optional vector lane but keeps the block`() = runTest {
        val factDao = FakeUserFactDao()
        factDao.insert(fact("a", "любит Тарковского"))
        val vectorDao = FakeFactVectorDao()
        vectorDao.upsert(vec("a", floatArrayOf(1f, 0f)))

        // Control: with a real (fast) clock the optional lane does run — so
        // the assertion below is about the budget, not about a never-taken path.
        val (control, controlEngine) = coordinator(factDao, vectorDao, elapsedNanos = { 0L })
        assertTrue(control.gather("что я люблю").contains("любит Тарковского"))
        assertEquals("vector lane runs when the budget is free", 1, controlEngine.embedCalls)

        // Started at t=0; every later probe already exceeds 20 ms.
        val spent = AtomicInteger()
        val jumpy: () -> Long = { if (spent.getAndIncrement() == 0) 0L else 1_000_000_000L }
        val (degraded, degradedEngine) = coordinator(factDao, vectorDao, elapsedNanos = jumpy)

        val block = degraded.gather("что я люблю")

        assertTrue(
            "the mandatory lexical block must still render",
            block.contains("любит Тарковского"),
        )
        assertEquals(
            "a spent budget skips the optional vector lane",
            0,
            degradedEngine.embedCalls,
        )
    }
}
