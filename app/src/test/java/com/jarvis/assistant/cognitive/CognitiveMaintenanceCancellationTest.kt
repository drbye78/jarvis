package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.BehaviorLogDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.HabitRuleDao
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.extract.FakeBehaviorLogDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeHabitRuleDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * P1-C (audit HIGH): the maintenance lane must honor the A8 cancellation
 * contract. The old `runCatching` steps swallowed CancellationException —
 * a cancel mid-maintenance aborted nothing, produced a storm of spurious
 * "step failed" ERROR logs, and the final stamp step failed fully silently.
 * These tests pin the [CognitiveCoordinator.onMaintenance] semantics after
 * the maintenanceStep rework: CE propagates immediately (zero failure logs,
 * no later step runs), while genuine per-step failures still log (content-
 * free) and never skip the rest of the pass (plan §9.1).
 */
class CognitiveMaintenanceCancellationTest {

    /** WARN/ERROR-counting tree (numeric priors — android.util.Log is off-limits on JVM). */
    private class CapturingTree : Timber.Tree() {
        val entries = mutableListOf<Pair<Int, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            synchronized(entries) { entries.add(priority to message) }
        }

        fun warnUp(): List<Pair<Int, String>> =
            synchronized(entries) { entries.filter { it.first >= PRI_WARN } }
    }

    private companion object {
        const val PRI_WARN = 5
        const val PRI_ERROR = 6
    }

    private val tree = CapturingTree()

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    private fun coordinator(
        factDao: UserFactDao = FakeUserFactDao(),
        behaviorLogDao: BehaviorLogDao = FakeBehaviorLogDao(),
        ruleDao: HabitRuleDao = FakeHabitRuleDao(),
        metaDao: FakeMemoryMetaDao = FakeMemoryMetaDao(),
        queueDao: ExtractionQueueDao = FakeExtractionQueueDao(),
        cloudEnabled: Boolean = false,
        behaviorEnabled: Boolean = false,
    ): Pair<CognitiveCoordinator, CoroutineScope> {
        val parent = CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Timber.e(e, "test: uncaught") },
        )
        val c = CognitiveCoordinator(
            deps = CognitiveDeps(
                factDao = factDao,
                queueDao = queueDao,
                metaDao = metaDao,
                messageDao = FakeMessageDao(),
                llm = object : LlmClient {
                    override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                        flowOf(LlmChunk.Done)
                },
                memoryEnabled = MutableStateFlow(true),
                autoExtractEnabled = MutableStateFlow(true),
                cloudEnabled = MutableStateFlow(cloudEnabled),
                sensitiveVisible = MutableStateFlow(true),
                ruleDao = ruleDao,
                behaviorLogDao = behaviorLogDao,
                behaviorEnabled = MutableStateFlow(behaviorEnabled),
            ),
            parentScope = parent,
        )
        return c to parent
    }

    /** factDao whose first allFacts() suspends until the caller cancels. */
    private class HangingFactDao(
        delegate: FakeUserFactDao,
        private val entered: CompletableDeferred<Unit>,
    ) : UserFactDao by delegate {
        override suspend fun allFacts(): List<UserFactEntity> {
            entered.complete(Unit)
            CompletableDeferred<Unit>().await() // cancellable; never resumes
            return emptyList()
        }
    }

    /** factDao whose first allFacts() throws once — a genuine step failure. */
    private class OnceFailingFactDao(
        private val delegate: FakeUserFactDao,
        private val failure: () -> Exception,
    ) : UserFactDao by delegate {
        private var fired = false
        override suspend fun allFacts(): List<UserFactEntity> {
            if (!fired) {
                fired = true
                throw failure()
            }
            return delegate.allFacts()
        }
    }

    @Test
    fun `cancel during maintenance aborts promptly - CE propagates, no failure-storm, no later steps`() {
        Timber.uprootAll()
        Timber.plant(tree)
        val entered = CompletableDeferred<Unit>()
        val laterStepCalls = java.util.concurrent.atomic.AtomicInteger()
        val countingLogDao = object : BehaviorLogDao by FakeBehaviorLogDao() {
            override suspend fun deleteOlderThan(cutoff: Long): Int {
                laterStepCalls.incrementAndGet()
                return 0
            }
        }
        val meta = FakeMemoryMetaDao()
        val (c, parent) = coordinator(
            factDao = HangingFactDao(FakeUserFactDao(), entered),
            behaviorLogDao = countingLogDao,
            metaDao = meta,
        )
        try {
            runBlocking {
                val job = launch(start = CoroutineStart.DEFAULT) { c.onMaintenance() }
                withTimeout(5_000) { entered.await() }
                job.cancel()
                withTimeout(5_000) { job.join() }

                // Old code: the cancel was swallowed 13 times — the run
                // limped through every remaining step logging a spurious
                // ERROR each. New code: the CE escapes at the first
                // suspension point…
                assertTrue("the maintenance job must end cancelled", job.isCancelled)
                // …so no later step ever runs…
                assertEquals("later steps must not execute", 0, laterStepCalls.get())
                assertEquals(
                    "the maintenance stamp must not be written",
                    null,
                    meta.values[MemoryMetaEntity.KEY_LAST_MAINTENANCE_AT],
                )
                // …and nothing is reported as a failure (the storm check).
                assertEquals(
                    "cancel must not produce any WARN+ log line",
                    emptyList<String>(),
                    tree.warnUp().map { it.second },
                )
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `a genuine step failure logs content-free ERROR and the remaining steps still run`() {
        Timber.uprootAll()
        Timber.plant(tree)
        val factDao = FakeUserFactDao()
        runBlocking {
            factDao.insert(
                UserFactEntity(
                    factId = "f1",
                    category = "OTHER",
                    subject = "user",
                    predicate = "likes",
                    value = "СЕКРЕТНЫЙ-ФРАГМЕНТ-о-пароле",
                    valueNormalized = "секретный фрагмент о пароле",
                    searchText = "секретн фрагмент парол",
                    confidence = 0.9f,
                    origin = "EXPLICIT",
                    status = "ACTIVE",
                    supersedesId = null,
                    contested = false,
                    sensitive = false,
                    sourceMessageId = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                    lastConfirmedAt = 1L,
                    lastRecalledAt = null,
                    recallCount = 0,
                ),
            )
        }
        val meta = FakeMemoryMetaDao()
        val (c, parent) = coordinator(
            factDao = OnceFailingFactDao(factDao) { IllegalStateException("db down") },
            metaDao = meta,
        )
        try {
            runBlocking { c.onMaintenance() }

            val failures = tree.warnUp()
            assertTrue(
                "the failed step must be logged at ERROR",
                failures.any { it.first == PRI_ERROR && it.second.contains("decay step failed") },
            )
            // A genuine failure never skips the rest (plan §9.1): the pass
            // completed and stamped — a path that used to be silent (:1103).
            assertTrue(
                "maintenance completed despite the failing first step",
                meta.values.containsKey(MemoryMetaEntity.KEY_LAST_MAINTENANCE_AT),
            )
            // Content-free lane: no WARN+ message carries fact content.
            assertTrue(
                "no WARN+ log message may carry fact content",
                failures.none { it.second.contains("СЕКРЕТНЫЙ") },
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `evaluateDueRules propagates cancellation instead of swallowing it`() {
        Timber.uprootAll()
        Timber.plant(tree)
        val throwing = object : HabitRuleDao by FakeHabitRuleDao() {
            override suspend fun candidateRules(): List<HabitRuleEntity> =
                throw CancellationException("cancel must propagate")
        }
        val (c, parent) = coordinator(ruleDao = throwing, behaviorEnabled = true)
        try {
            runBlocking {
                val e = runCatching { c.evaluateDueRules() }.exceptionOrNull()
                assertTrue(
                    "candidateRules cancellation must propagate (old code returned silently)",
                    e is CancellationException,
                )
                assertEquals(
                    "a cancel is not a query failure — no WARN/ERROR may be logged",
                    0,
                    tree.warnUp().size,
                )
            }
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `drain loop surfaces pendingCount cancellation without a spurious failure log`() {
        Timber.uprootAll()
        Timber.plant(tree)
        val touched = CompletableDeferred<Unit>()
        // The shared fake is final — wrap it via interface delegation and
        // override just the counter the drain loop polls.
        val baseQueue = FakeExtractionQueueDao()
        val cancelingQueue = object : ExtractionQueueDao by baseQueue {
            override suspend fun pendingCount(): Int {
                if (!touched.isCompleted) touched.complete(Unit)
                throw CancellationException("loop cancelled")
            }
        }
        val (c, parent) = coordinator(queueDao = cancelingQueue, cloudEnabled = true)
        try {
            runBlocking {
                c.startQueueLoop()
                withTimeout(5_000) { touched.await() }
                // Let the loop unwind (the drain job runs on the
                // coordinator's IO-backed scope), then check the storm
                // signature.
                delay(250)
                // Old code: the CE was caught as "Exception" → WARN
                // "pendingCount failed" + the loop kept running. New code:
                // the CE ends the drain job quietly.
                val spam = tree.warnUp().filter { it.second.contains("pendingCount") }
                assertEquals("no failure-storm on cancel", 0, spam.size)
            }
        } finally {
            parent.cancel()
        }
    }
}
