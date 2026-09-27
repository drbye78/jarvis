package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.extract.FakeBehaviorLogDao
import com.jarvis.assistant.cognitive.extract.FakeCommandEventDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeSessionSummaryDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.data.MessageEntity
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freeze contract: with the master `memoryEnabled` switch OFF the nightly
 * pass must not touch stored memory — no decay, no archive, no superseded
 * hard-delete, no vector/entity/summary work — while behaviour-layer
 * retention and the maintenance stamp keep running. The manual wipe is the
 * only path that deletes stored memory, so maintenance off must be a freeze,
 * not a silent destruction.
 */
class MemoryFreezeMaintenanceTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L
    private val parents = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        parents.forEach { it.cancel() }
    }

    private fun coordinator(
        memoryEnabled: Boolean,
        factDao: FakeUserFactDao,
        eventDao: FakeCommandEventDao = FakeCommandEventDao(),
        behaviorLogDao: FakeBehaviorLogDao = FakeBehaviorLogDao(),
        summaryDao: FakeSessionSummaryDao = FakeSessionSummaryDao(),
        metaDao: FakeMemoryMetaDao = FakeMemoryMetaDao(),
        queueDao: FakeExtractionQueueDao = FakeExtractionQueueDao(),
        messageDao: FakeMessageDao = FakeMessageDao(),
    ): CognitiveCoordinator {
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        parents += parent
        return CognitiveCoordinator(
            deps = CognitiveDeps(
                factDao = factDao,
                queueDao = queueDao,
                metaDao = metaDao,
                messageDao = messageDao,
                llm = object : LlmClient {
                    override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flowOf(LlmChunk.Done)
                },
                memoryEnabled = MutableStateFlow(memoryEnabled),
                autoExtractEnabled = MutableStateFlow(true),
                cloudEnabled = MutableStateFlow(false),
                sensitiveVisible = MutableStateFlow(true),
                eventDao = eventDao,
                behaviorLogDao = behaviorLogDao,
                summaryDao = summaryDao,
                nowMs = { now },
            ),
            parentScope = parent,
        )
    }

    private fun fact(
        id: String,
        status: String,
        confidence: Float = 0.9f,
        ageDays: Long = 0,
    ) = UserFactEntity(
        factId = id,
        category = "OTHER",
        subject = "user",
        predicate = "p",
        value = id,
        valueNormalized = id,
        searchText = id,
        confidence = confidence,
        origin = "INFERRED",
        status = status,
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        createdAt = now - (ageDays + 1) * day,
        updatedAt = now - ageDays * day,
        lastConfirmedAt = now - ageDays * day,
        lastRecalledAt = null,
        recallCount = 0,
    )

    // ---- 1. superseded retention is frozen --------------------------------

    @Test
    fun `superseded fact past retention survives maintenance while memory is off`() = runBlocking {
        val facts = FakeUserFactDao()
        facts.insert(fact("old-chain", "SUPERSEDED", ageDays = 120))
        coordinator(memoryEnabled = false, factDao = facts).onMaintenance()
        assertNotNull(
            "memory-off must not hard-delete stored facts",
            facts.byFactId("old-chain"),
        )
    }

    @Test
    fun `superseded fact past retention is deleted when memory is on`() = runBlocking {
        val facts = FakeUserFactDao()
        facts.insert(fact("old-chain", "SUPERSEDED", ageDays = 120))
        coordinator(memoryEnabled = true, factDao = facts).onMaintenance()
        assertNull(
            "retention must still run with memory on (proves the gate, not a broken retention)",
            facts.byFactId("old-chain"),
        )
    }

    // ---- 2. decay/archive is frozen ---------------------------------------

    @Test
    fun `below-floor active fact is not archived while memory is off`() = runBlocking {
        val facts = FakeUserFactDao()
        facts.insert(fact("weak", "ACTIVE", confidence = 0.21f, ageDays = 40))
        coordinator(memoryEnabled = false, factDao = facts).onMaintenance()
        assertEquals("ACTIVE", facts.byFactId("weak")!!.status)
    }

    @Test
    fun `below-floor active fact is archived when memory is on`() = runBlocking {
        val facts = FakeUserFactDao()
        facts.insert(fact("weak", "ACTIVE", confidence = 0.21f, ageDays = 40))
        coordinator(memoryEnabled = true, factDao = facts).onMaintenance()
        assertEquals("ARCHIVED", facts.byFactId("weak")!!.status)
    }

    // ---- 3. non-memory retention keeps running ----------------------------

    @Test
    fun `behaviour retention still runs while memory is off`() = runBlocking {
        val events = FakeCommandEventDao()
        events.rows += CommandEventEntity(
            at = now - 100 * day,
            tool = "playMusic",
            argsFingerprint = "q:x",
            ok = true,
            latencyMs = 1,
            origin = "VOICE",
        )
        coordinator(memoryEnabled = false, factDao = FakeUserFactDao(), eventDao = events).onMaintenance()
        assertEquals(
            "non-memory retention must keep running while memory is off",
            0,
            events.rows.size,
        )
    }

    // ---- 4. the stamp is still written ------------------------------------

    @Test
    fun `maintenance stamp is still written while memory is off`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        coordinator(
            memoryEnabled = false,
            factDao = FakeUserFactDao(),
            metaDao = meta,
        ).onMaintenance()
        assertEquals(
            now.toString(),
            meta.values[MemoryMetaEntity.KEY_LAST_MAINTENANCE_AT],
        )
    }

    // ---- 5. backfill is gated ---------------------------------------------

    @Test
    fun `backfill enqueues nothing while memory is off`() = runBlocking {
        val messages = FakeMessageDao()
        messages.rows[1] = MessageEntity(id = 1, role = "user", content = "меня зовут Алексей")
        messages.rows[2] = MessageEntity(id = 2, role = "user", content = "люблю Тарковского")
        val queue = FakeExtractionQueueDao()
        val meta = FakeMemoryMetaDao()
        val c = coordinator(
            memoryEnabled = false,
            factDao = FakeUserFactDao(),
            queueDao = queue,
            messageDao = messages,
            metaDao = meta,
        )
        assertEquals(0, c.backfillRecent())
        assertTrue("no extraction rows may be created while memory is off", queue.rows.isEmpty())
        assertNull(
            "memory-off must not consume the one-shot backfill flag",
            meta.values[MemoryMetaEntity.KEY_EXTRACTION_BACKFILL_DONE],
        )
    }

    @Test
    fun `backfill enqueues while memory is on`() = runBlocking {
        val messages = FakeMessageDao()
        messages.rows[1] = MessageEntity(id = 1, role = "user", content = "меня зовут Алексей")
        messages.rows[2] = MessageEntity(id = 2, role = "user", content = "люблю Тарковского")
        val queue = FakeExtractionQueueDao()
        val meta = FakeMemoryMetaDao()
        val c = coordinator(
            memoryEnabled = true,
            factDao = FakeUserFactDao(),
            queueDao = queue,
            messageDao = messages,
            metaDao = meta,
        )
        assertEquals(2, c.backfillRecent())
        assertEquals(2, queue.rows.size)
        assertNotNull(meta.values[MemoryMetaEntity.KEY_EXTRACTION_BACKFILL_DONE])
    }
}
