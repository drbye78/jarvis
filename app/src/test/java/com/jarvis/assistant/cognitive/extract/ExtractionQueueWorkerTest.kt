package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import com.jarvis.assistant.data.MessageEntity
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §1.4 fixture tests: gate → batch → cloud call → strict
 * validation → normalization → durable states. Idempotency, batching,
 * backoff and quarantine all run against the in-memory DAO fakes.
 */
class ExtractionQueueWorkerTest {

    private class FakeLlm(
        private val respond: suspend (ChatRequest) -> String,
    ) : LlmClient {
        val requests = mutableListOf<ChatRequest>()
        var calls = 0

        override fun chatStream(request: ChatRequest): Flow<LlmChunk> {
            calls++
            requests.add(request)
            return flow { emit(LlmChunk.Text(respond(request))); emit(LlmChunk.Done) }
        }

        override suspend fun chatOnce(request: ChatRequest): String = respond(request).also {
            calls++
            requests.add(request)
        }
    }

    private var idCounter = 0

    private fun factJson(messageId: Long, value: String, evidence: String, confidence: Double = 0.9) =
        """{"subject":"user","predicate":"likes","value":"$value","confidence":$confidence,""" +
            """"evidence":"$evidence","messageId":$messageId}"""

    private fun seed(vararg utterances: String): Pair<FakeExtractionQueueDao, FakeMessageDao> {
        val queue = FakeExtractionQueueDao()
        val messages = FakeMessageDao()
        var id = 10L
        utterances.forEach { text ->
            val messageId = id++
            messages.rows[messageId] = MessageEntity(id = messageId, role = "user", content = text)
            queue.rows[messageId] = ExtractionQueueEntity(
                messageId = messageId,
                createdAt = 0,
                updatedAt = 0,
            )
        }
        return queue to messages
    }

    @Test
    fun `one batch of three messages produces one cloud call`() = runTest {
        val (queue, messages) = seed("меня зовут Алексей", "люблю Тарковского", "работаю в Яндексе")
        val llm = FakeLlm {
            """{"facts":[${factJson(10, "Алексей", "меня зовут Алексей")},${factJson(11, "Тарковского", "люблю Тарковского")}]}"""
        }
        val worker = ExtractionQueueWorker(
            queue, FakeUserFactDao(), FakeMemoryMetaDao(), messages, llm,
            normalizer = FactNormalizer(nowMs = { 1L }, newId = { "f-${idCounter++}" }),
        )

        val report = worker.drainOnce()!!

        assertEquals(1, llm.calls)
        assertEquals(3, report.messages)
        assertEquals(2, report.extracted)
        assertEquals(3, queue.rows.values.count { it.state == "DONE" })
    }

    @Test
    fun `queue is exactly-once per message`() = runTest {
        val queue = FakeExtractionQueueDao()
        // The row PK is messageId itself → a successful insert returns it.
        assertEquals(42L, queue.enqueue(ExtractionQueueEntity(42, createdAt = 0, updatedAt = 0)))
        assertEquals(-1L, queue.enqueue(ExtractionQueueEntity(42, createdAt = 0, updatedAt = 0)))
        assertEquals(1, queue.rows.size)
    }

    @Test
    fun `pruned message completes with zero facts instead of erroring`() = runTest {
        val queue = FakeExtractionQueueDao()
        queue.rows[99] = ExtractionQueueEntity(99, createdAt = 0, updatedAt = 0)
        val worker = ExtractionQueueWorker(
            queue, FakeUserFactDao(), FakeMemoryMetaDao(), FakeMessageDao(),
            FakeLlm { """{"facts":[]}""" },
        )
        val report = worker.drainOnce()!!
        assertEquals(0, report.extracted)
        assertEquals("DONE", queue.rows[99]!!.state)
    }

    @Test
    fun `unparseable response quarantines the batch`() = runTest {
        val (queue, messages) = seed("меня зовут Алексей")
        val worker = ExtractionQueueWorker(
            queue, FakeUserFactDao(), FakeMemoryMetaDao(), messages,
            FakeLlm { "Извините, я не могу ответить JSON-ом." },
        )
        val report = worker.drainOnce()!!
        assertTrue(report.quarantined)
        assertEquals("QUARANTINED", queue.rows[10]!!.state)
    }

    @Test
    fun `hallucinated evidence never reaches storage`() = runTest {
        val (queue, messages) = seed("люблю Тарковского")
        val worker = ExtractionQueueWorker(
            queue, FakeUserFactDao(), FakeMemoryMetaDao(), messages,
            FakeLlm {
                // Evidence does NOT occur in the utterance → the fact is dropped.
                """{"facts":[${factJson(10, "Пушкина", "обожаю Пушкина", 0.99)}]}"""
            },
        )
        val report = worker.drainOnce()!!
        assertEquals(0, report.extracted)
        assertEquals(1, report.dropped)
        assertEquals("DONE", queue.rows[10]!!.state)
    }

    @Test
    fun `transport failure releases rows back to pending and flags backoff`() = runTest {
        val (queue, messages) = seed("меня зовут Алексей")
        val llm = object : LlmClient {
            override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                throw LlmHttpException(429)
        }
        val worker = ExtractionQueueWorker(
            queue, FakeUserFactDao(), FakeMemoryMetaDao(), messages, llm,
        )
        val report = worker.drainOnce()!!
        assertTrue(worker.lastBatchTransportFailed)
        assertEquals("PENDING", queue.rows[10]!!.state)
        assertEquals(1, queue.rows[10]!!.attempt) // attempt persisted
        assertEquals(0, report.extracted)
    }

    @Test
    fun `poison rows quarantine after max attempts instead of retrying forever`() = runTest {
        val (queue, messages) = seed("меня зовут Алексей")
        val llm = object : LlmClient {
            override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                throw IOExceptionSim()
        }
        val worker = ExtractionQueueWorker(
            queue, FakeUserFactDao(), FakeMemoryMetaDao(), messages, llm,
        )
        // Attempts 1..MAX: transport failure → PENDING; after MAX → quarantine.
        repeat(ExtractionQueueEntity.MAX_ATTEMPTS) { worker.drainOnce() }
        assertEquals("PENDING", queue.rows[10]!!.state)
        worker.drainOnce()
        assertEquals("QUARANTINED", queue.rows[10]!!.state)
    }

    @Test
    fun `backfill enqueues retained user messages once`() = runTest {
        val messages = FakeMessageDao()
        repeat(5) { i ->
            messages.rows[(100L + i)] =
                MessageEntity(id = 100L + i, role = "user", content = "реплика $i")
        }
        messages.rows[200] = MessageEntity(id = 200, role = "assistant", content = "ответ")
        val meta = FakeMemoryMetaDao()
        val worker = ExtractionQueueWorker(
            FakeExtractionQueueDao(), FakeUserFactDao(), meta, messages,
            FakeLlm { """{"facts":[]}""" },
        )
        val first = worker.backfillRecent()
        assertEquals(5, first)
        val second = worker.backfillRecent()
        assertEquals(-1, second) // one-shot via extractionBackfillDone
    }

    @Test
    fun `extraction gate skips pure tool traffic`() {
        assertTrue(ExtractionGate.shouldExtract("Джарвис, запомни, что жена Маша любит пионы"))
        assertTrue(ExtractionGate.shouldExtract("Меня зовут Алексей"))
        assertTrue(ExtractionGate.shouldExtract("Я люблю старые фильмы Тарковского"))
        assertTrue(ExtractionGate.shouldExtract("Мой начальник Олег работает в офисе"))
        assertTrue(ExtractionGate.shouldExtract("У меня день рождения 12 апреля"))
        // Pure tool traffic — skipped (plan §6.1: 50–70 % skip rate).
        assertFalse(ExtractionGate.shouldExtract("включи джаз"))
        assertFalse(ExtractionGate.shouldExtract("погода на завтра"))
        assertFalse(ExtractionGate.shouldExtract("таймер на 10 минут"))
        assertFalse(ExtractionGate.shouldExtract("какой сейчас час?"))
        assertFalse(ExtractionGate.shouldExtract("да"))
        assertFalse(ExtractionGate.shouldExtract("останови музыку"))
    }

    @Test
    fun `parser tolerates fenced and commented json`() {
        val parser = ExtractionParser()
        val batch = listOf(1L to "меня зовут Алексей")
        val fenced = """Вот результат: ```json
            {"facts":[${factJson(1, "Алексей", "зовут Алексей")}]}
            ``` """
        val ok = parser.parse(fenced, batch)
        assertTrue(ok is ExtractionParser.Result.Ok)
        assertEquals(1, (ok as ExtractionParser.Result.Ok).facts.size)
    }

    private class IOExceptionSim : java.io.IOException("network down")
}
