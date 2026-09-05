package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.cognitive.data.SessionSummaryEntity
import com.jarvis.assistant.data.MessageEntity
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.llm.LlmHttpException
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN 2.5: the summarizer's cursor discipline (plan Appendix E:
 * SummarizerCursorTest) — the cursor advances ONLY after a successful
 * commit, the DAILY digest runs once per day, and the prompt payload is
 * budget-truncated.
 */
class SummarizerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class ScriptedLlm(
        private val text: String,
        /** How many ATTEMPTS fail before the scripted text flows. */
        private val failAttempts: Int = 0,
    ) : LlmClient {
        var calls = 0
        var lastRequest: ChatRequest? = null

        override fun chatStream(request: ChatRequest): Flow<LlmChunk> = flow {
            calls++
            lastRequest = request
            if (calls <= failAttempts) {
                // 400 = FATAL (not transient): withLlmRetry rethrows it
                // immediately instead of retrying — deterministic for tests.
                throw LlmHttpException(400)
            }
            emit(LlmChunk.Text(text))
            emit(LlmChunk.Done)
        }
    }

    private fun row(id: Long, role: String, text: String, at: Long) = MessageEntity(
        id = id, role = role, content = text, createdAt = at,
    )

    @Test
    fun `captureDoomed with nothing speakable still advances the cursor`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val messages = FakeMessageDao(row(1, "tool", """{"x":1}""", 100))
        val llm = ScriptedLlm("summary")
        val s = Summarizer(
            FakeSessionSummaryDao(), messages, meta, llm,
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
        )
        s.captureDoomed(1)
        assertEquals("1", meta.get(MemoryMetaEntity.KEY_LAST_SUMMARIZED_MESSAGE_ID))
        assertEquals(0, llm.calls)
    }

    @Test
    fun `successful batch writes a SESSION row and advances the cursor`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val messages = FakeMessageDao(
            row(1, "user", "Включи джаз", 100),
            row(2, "assistant", "Включаю.", 101),
        )
        val dao = FakeSessionSummaryDao()
        val llm = ScriptedLlm("Пользователь просил включить джаз.")
        val s = Summarizer(dao, messages, meta, llm,
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
        )
        val made = s.summarizeBatch(messages.all().filter { it.role != "tool" }, upToMessageId = 2)
        assertNotNull(made)
        assertEquals(SessionSummaryEntity.KIND_SESSION, made!!.kind)
        assertEquals("m1", made.modelId)
        assertEquals("2", meta.get(MemoryMetaEntity.KEY_LAST_SUMMARIZED_MESSAGE_ID))
        assertEquals(1, llm.calls)
    }

    @Test
    fun `cloud failure leaves the cursor alone and writes nothing`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val messages = FakeMessageDao(row(1, "user", "Привет", 100))
        val dao = FakeSessionSummaryDao()
        val llm = ScriptedLlm("ignored", failAttempts = 2)
        val s = Summarizer(dao, messages, meta, llm,
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
        )
        val made = s.summarizeBatch(messages.all(), upToMessageId = 1)
        assertNull(made)
        assertTrue(dao.rows.isEmpty())
        assertNull(meta.get(MemoryMetaEntity.KEY_LAST_SUMMARIZED_MESSAGE_ID))
    }

    @Test
    fun `cloud or memory switch off means no summarization`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val messages = FakeMessageDao(row(1, "user", "Привет", 100))
        val llm = ScriptedLlm("x")
        val cloudOff = Summarizer(FakeSessionSummaryDao(), messages, meta, llm,
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(false),
            modelId = { "m1" },
        )
        assertNull(cloudOff.summarizeBatch(messages.all(), upToMessageId = 1))
        val memoryOff = Summarizer(FakeSessionSummaryDao(), messages, meta, llm,
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(false),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
        )
        assertNull(memoryOff.summarizeBatch(messages.all(), upToMessageId = 1))
        assertEquals(0, llm.calls)
    }

    @Test
    fun `daily digest runs once per day and needs two sessions`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val dao = FakeSessionSummaryDao()
        val messages = FakeMessageDao()
        val llm = ScriptedLlm("Итог дня.")
        val now = 100L * 24 * 60 * 60_000L + 20L * 60 * 60_000L
        val s = Summarizer(dao, messages, meta, llm,
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
            nowMs = { now },
        )
        // One session today — below the MIN_SESSIONS_FOR_DAILY bar.
        dao.insert(
            SessionSummaryEntity(
                kind = SessionSummaryEntity.KIND_SESSION, fromMessageId = 1, toMessageId = 2,
                fromAt = now, toAt = now, text = "— что-то", modelId = "m1",
                tokensIn = 10, tokensOut = 2, createdAt = now,
            ),
        )
        assertNull(s.dailyDigest())
        // Second session — digest fires.
        dao.insert(
            SessionSummaryEntity(
                kind = SessionSummaryEntity.KIND_SESSION, fromMessageId = 3, toMessageId = 4,
                fromAt = now, toAt = now, text = "— ещё что-то", modelId = "m1",
                tokensIn = 10, tokensOut = 2, createdAt = now,
            ),
        )
        assertNotNull(s.dailyDigest())
        // Same day again — a no-op (keyed by epoch day).
        assertNull(s.dailyDigest())
        assertEquals(1, llm.calls)
    }

    @Test
    fun `renderForPrompt joins the daily digest with newer sessions and truncates`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val dao = FakeSessionSummaryDao()
        val messages = FakeMessageDao()
        val s = Summarizer(dao, messages, meta, ScriptedLlm("x"),
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
        )
        assertTrue(s.renderForPrompt().isEmpty())

        val base = 100L * 24 * 60 * 60_000L
        dao.insert(
            SessionSummaryEntity(
                kind = SessionSummaryEntity.KIND_DAILY, fromMessageId = 1, toMessageId = 2,
                fromAt = base, toAt = base, text = "Итог дня", modelId = "m1",
                tokensIn = 1, tokensOut = 1, createdAt = base,
            ),
        )
        dao.insert(
            SessionSummaryEntity(
                kind = SessionSummaryEntity.KIND_SESSION, fromMessageId = 3, toMessageId = 4,
                fromAt = base + 1, toAt = base + 1, text = "Позже говорили о музыке",
                modelId = "m1", tokensIn = 1, tokensOut = 1, createdAt = base + 1,
            ),
        )
        val rendered = s.renderForPrompt()
        assertTrue(rendered.contains("Итог дня"))
        assertTrue(rendered.contains("музыке"))
        // The section is hard-bounded by the plan's ≤600 char budget.
        assertTrue(rendered.length <= Summarizer.PROMPT_CHAR_BUDGET)
    }

    @Test
    fun `renderForPrompt truncates by whole lines within the budget`() = runBlocking {
        val meta = FakeMemoryMetaDao()
        val dao = FakeSessionSummaryDao()
        val s = Summarizer(dao, FakeMessageDao(), meta, ScriptedLlm("x"),
            memoryEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            cloudEnabled = kotlinx.coroutines.flow.MutableStateFlow(true),
            modelId = { "m1" },
        )
        val base = 100L * 24 * 60 * 60_000L
        repeat(40) { i ->
            dao.insert(
                SessionSummaryEntity(
                    kind = SessionSummaryEntity.KIND_DAILY, fromMessageId = i.toLong(), toMessageId = i.toLong(),
                    fromAt = base + i, toAt = base + i,
                    text = "Строка номер $i с каким-то содержанием длиннее двадцати символов",
                    modelId = "m1", tokensIn = 1, tokensOut = 1, createdAt = base + i,
                ),
            )
        }
        val rendered = s.renderForPrompt()
        assertTrue(rendered.length <= Summarizer.PROMPT_CHAR_BUDGET)
        // No mid-line cut: every rendered line is complete.
        rendered.lines().forEach { assertTrue(it.isEmpty() || it.startsWith("— ")) }
    }
}
