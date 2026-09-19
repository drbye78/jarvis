package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.FactVectorEntity
import com.jarvis.assistant.cognitive.embed.EmbeddingEngine
import com.jarvis.assistant.cognitive.embed.VectorMath
import com.jarvis.assistant.cognitive.extract.FakeEntityDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeFactVectorDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * N7 (REMEDIATION_PLAN): turning `memory.cloudEnabled` OFF must delete every
 * CLOUD vector space, not merely stop reading it — the embeddings of user
 * facts would otherwise stay in Room forever. Two entry points are pinned:
 * the live StateFlow watch (immediate purge) and the nightly-maintenance
 * backstop (a disable that happened while the app was killed).
 */
class CloudVectorPurgeTest {

    private val cloudEnabled = MutableStateFlow(true)

    private fun coordinator(vectorDao: FakeFactVectorDao): CognitiveCoordinator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return CognitiveCoordinator(
            deps = CognitiveDeps(
                factDao = FakeUserFactDao(),
                queueDao = FakeExtractionQueueDao(),
                metaDao = FakeMemoryMetaDao(),
                messageDao = FakeMessageDao(),
                llm = object : LlmClient {
                    override fun chatStream(request: ChatRequest) = flowOf(LlmChunk.Done)
                },
                memoryEnabled = MutableStateFlow(true),
                autoExtractEnabled = MutableStateFlow(false),
                cloudEnabled = cloudEnabled,
                sensitiveVisible = MutableStateFlow(true),
                vectorDao = vectorDao,
                entityDao = FakeEntityDao(),
                embedderChoice = MutableStateFlow("OFF"),
                // Pin the coordinator's own scope (and the CPU lane) to
                // Unconfined: the purge must be observed at the same virtual
                // instant as the StateFlow flip, with no thread hop to race.
                cpuDispatcher = Dispatchers.Unconfined,
                cognitiveDispatcher = Dispatchers.Unconfined,
            ),
            parentScope = scope,
        )
    }

    private fun vec(factId: String, engineId: String) = FactVectorEntity(
        factId = factId,
        engineId = engineId,
        dim = 2,
        vec = VectorMath.floatsToBytes(floatArrayOf(1f, 0f)),
        createdAt = 1L,
    )

    @Test
    fun `flipping cloudEnabled off purges cloud vectors and keeps local`() = runTest {
        val vectorDao = FakeFactVectorDao()
        vectorDao.upsert(vec("local-fact", EmbeddingEngine.LOCAL_ID))
        vectorDao.upsert(vec("cloud-fact", EmbeddingEngine.CLOUD_ID))
        assertEquals(2, vectorDao.rows.size)

        val c = coordinator(vectorDao)
        c.startCloudPurgeWatch()

        cloudEnabled.value = false
        testScheduler.advanceUntilIdle()

        assertTrue(
            "the cloud vector space must be gone after the flip",
            vectorDao.rows.values.none { it.engineId == EmbeddingEngine.CLOUD_ID },
        )
        assertEquals(
            "the on-device LOCAL row must be untouched",
            1,
            vectorDao.countForEngine(EmbeddingEngine.LOCAL_ID),
        )
        assertEquals(1, vectorDao.rows.size)
    }

    @Test
    fun `cold start maintenance purges cloud vectors when the switch is already off`() = runTest {
        cloudEnabled.value = false
        val vectorDao = FakeFactVectorDao()
        vectorDao.upsert(vec("local-fact", EmbeddingEngine.LOCAL_ID))
        vectorDao.upsert(vec("cloud-fact", EmbeddingEngine.CLOUD_ID))

        val c = coordinator(vectorDao)
        c.onMaintenance()

        assertTrue(
            "the cloud vector space must be gone after the backstop",
            vectorDao.rows.values.none { it.engineId == EmbeddingEngine.CLOUD_ID },
        )
        assertEquals(
            "the on-device LOCAL row must be untouched",
            1,
            vectorDao.countForEngine(EmbeddingEngine.LOCAL_ID),
        )
        assertEquals(1, vectorDao.rows.size)
    }
}
