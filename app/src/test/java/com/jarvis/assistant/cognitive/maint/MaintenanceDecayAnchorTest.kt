package com.jarvis.assistant.cognitive.maint

import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.cognitive.CognitiveDeps
import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.pow

/**
 * REMEDIATION_PLAN Phase 4: decay must be derived from an immutable anchor
 * so a nightly job is IDEMPOTENT. Before the fix the job persisted the
 * already-decayed confidence without refreshing `updatedAt`, so pass k
 * re-decayed its own output: after n passes the exponent was the triangular
 * sum n(n+1)/2, not n. These tests drive the real caller
 * ([CognitiveCoordinator.onMaintenance] → `decayInactiveFacts`) with an
 * injected clock across consecutive nightly passes.
 */
class MaintenanceDecayAnchorTest {

    private val day = 86_400_000L
    private val anchorAt = 1_700_000_000_000L

    private fun fact(
        confidence: Float = 1f,
        updatedAt: Long = anchorAt,
        decayAnchorAt: Long = updatedAt,
        decayAnchorConfidence: Float = confidence,
    ) = UserFactEntity(
        factId = "f1",
        category = "OTHER",
        subject = "user",
        predicate = "likes",
        value = "чай",
        valueNormalized = "чай",
        searchText = "чай",
        confidence = confidence,
        origin = "INFERRED",
        status = "ACTIVE",
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastConfirmedAt = updatedAt,
        lastRecalledAt = null,
        recallCount = 0,
        decayAnchorConfidence = decayAnchorConfidence,
        decayAnchorAt = decayAnchorAt,
    )

    private fun coordinator(
        factDao: UserFactDao,
        nowMs: () -> Long,
    ): Pair<CognitiveCoordinator, CoroutineScope> {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val c = CognitiveCoordinator(
            deps = CognitiveDeps(
                factDao = factDao,
                queueDao = FakeExtractionQueueDao(),
                metaDao = FakeMemoryMetaDao(),
                messageDao = FakeMessageDao(),
                llm = object : LlmClient {
                    override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                        flowOf(LlmChunk.Done)
                },
                memoryEnabled = MutableStateFlow(true),
                autoExtractEnabled = MutableStateFlow(true),
                cloudEnabled = MutableStateFlow(false),
                sensitiveVisible = MutableStateFlow(true),
                nowMs = nowMs,
            ),
            parentScope = parent,
        )
        return c to parent
    }

    @Test
    fun `anchored fact is c0 times 0_99 to the n after n nightly passes`() = runBlocking {
        val factDao = FakeUserFactDao()
        factDao.insert(fact(confidence = 1f))
        var now = anchorAt
        val (c, parent) = coordinator(factDao) { now }
        try {
            val passes = 5
            repeat(passes) { i ->
                now = anchorAt + (31 + i) * day // day 31, 32, 33, …
                c.onMaintenance()
            }
            val stored = factDao.byFactId("f1")!!
            // c0 = 1.0 → 0.99^n. The old compounding bug produced
            // 0.99^(n(n+1)/2) = 0.99^15 ≈ 0.860 after 5 passes.
            assertEquals(
                "anchored decay must be c0 * 0.99^n, not the triangular exponent",
                0.99.pow(passes),
                stored.confidence.toDouble(),
                1e-4,
            )
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `unanchored row is latched on first decay and stays idempotent`() = runBlocking {
        val factDao = FakeUserFactDao()
        factDao.insert(fact(confidence = 1f, decayAnchorAt = 0L))
        var now = anchorAt
        val (c, parent) = coordinator(factDao) { now }
        try {
            // First pass at day 35: historical fallback decays to 0.99^5 and
            // latches the anchor at the pre-decay confidence / updatedAt.
            now = anchorAt + 35 * day
            c.onMaintenance()
            val afterFirst = factDao.byFactId("f1")!!
            assertEquals(0.99.pow(5), afterFirst.confidence.toDouble(), 1e-4)
            assertEquals(anchorAt, afterFirst.decayAnchorAt)
            assertEquals(1f, afterFirst.decayAnchorConfidence, 1e-6f)

            // Second pass at day 36: 0.99^6 total, not 0.99^(5+6).
            now = anchorAt + 36 * day
            c.onMaintenance()
            val afterSecond = factDao.byFactId("f1")!!
            assertEquals(0.99.pow(6), afterSecond.confidence.toDouble(), 1e-4)
        } finally {
            parent.cancel()
        }
    }
}
