package com.jarvis.assistant.cognitive.tools

import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §6.4: the honest-outcome contract of the memory tools —
 * including the §12.4 switches (disabled surface, sensitive filtering) and
 * the two-step forget token handshake.
 */
class MemoryToolsTest {

    private val memoryEnabled = MutableStateFlow(true)
    private val autoExtract = MutableStateFlow(false)
    private val cloudEnabled = MutableStateFlow(true)
    private val sensitiveVisible = MutableStateFlow(true)

    private fun coordinator(
        factDao: FakeUserFactDao = FakeUserFactDao(),
    ): Pair<CognitiveCoordinator, FakeUserFactDao> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val coordinator = CognitiveCoordinator(
            factDao = factDao,
            queueDao = FakeExtractionQueueDao(),
            metaDao = FakeMemoryMetaDao(),
            llm = object : LlmClient {
                override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                    throw AssertionError("tools must never call the LLM")
            },
            messageDao = FakeMessageDao(),
            memoryEnabled = memoryEnabled,
            autoExtractEnabled = autoExtract,
            cloudEnabled = cloudEnabled,
            sensitiveVisible = sensitiveVisible,
            strings = ToolStrings.Default,
            parentScope = scope,
            nowMs = { 1_000L },
        )
        return coordinator to factDao
    }

    @Test
    fun `remember writes and recall finds - explicit writes never call the LLM`() = runTest {
        val (c, dao) = coordinator()
        val outcome = c.rememberFact("зовут Алексей", category = "name", subject = null)
        assertTrue(outcome is MemoryOutcome.Written)
        assertEquals(1, dao.rows.size)
        assertEquals("EXPLICIT", dao.rows.values.first().origin)
        assertEquals(1.0f, dao.rows.values.first().confidence)

        val recall = c.recallFacts("как меня зовут")
        assertTrue(recall is MemoryOutcome.Recalled)
        assertTrue((recall as MemoryOutcome.Recalled).facts.any { it.contains("Алексей") })
    }

    @Test
    fun `re-remembering the same fact merges instead of duplicating`() = runTest {
        val (c, dao) = coordinator()
        c.rememberFact("зовут Алексей", "name", null)
        val second = c.rememberFact("зовут Алексей", "name", null)
        assertTrue(second is MemoryOutcome.Merged)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `strong conflicting claim asks instead of overwriting`() = runTest {
        val (c, dao) = coordinator()
        c.rememberFact("работает в Яндексе", "works_at", null)
        val second = c.rememberFact("работает в Сбере", "works_at", null)
        assertTrue("both strong (1.0) → contest", second is MemoryOutcome.NeedsClarification)
        assertEquals(2, dao.rows.size)
        assertTrue(dao.rows.values.all { it.contested })
    }

    @Test
    fun `disabled memory reports honestly and writes nothing`() = runTest {
        memoryEnabled.value = false
        val (c, dao) = coordinator()
        assertTrue(c.rememberFact("зовут Алексей", "name", null) is MemoryOutcome.Disabled)
        assertTrue(c.recallFacts(null) is MemoryOutcome.Disabled)
        assertTrue(c.forgetFact("Алексей", false, null) is MemoryOutcome.Disabled)
        assertEquals(0, dao.rows.size)
        memoryEnabled.value = true
    }

    @Test
    fun `sensitive facts are filtered from prompts and recall when the switch is off`() = runTest {
        val (c, _) = coordinator()
        c.rememberFact("аллергия на пыльцу", "health", null)
        sensitiveVisible.value = false
        assertTrue(c.recallFacts("аллергия") is MemoryOutcome.RecallEmpty)
        sensitiveVisible.value = true
        val recall = c.recallFacts("аллергия")
        assertTrue(recall is MemoryOutcome.Recalled)
        // §12.4-2: visible-but-marked — the spoken line carries the mark.
        assertTrue((recall as MemoryOutcome.Recalled).facts.any { it.contains("чувствительно") })
    }

    @Test
    fun `forget is two-step and refuses confirmation without the token`() = runTest {
        val (c, dao) = coordinator()
        c.rememberFact("любит Тарковского", "likes", null)
        val candidates = c.forgetFact("Тарковского", confirmed = false, token = null)
        assertTrue(candidates is MemoryOutcome.ForgetCandidates)
        val token = (candidates as MemoryOutcome.ForgetCandidates).confirmToken

        // Refusal path: no token, wrong token.
        assertTrue(c.forgetFact("Тарковского", true, null) is MemoryOutcome.ForgetCandidates)
        assertTrue(c.forgetFact("Тарковского", true, "deadbeef") is MemoryOutcome.ForgetCandidates)

        val done = c.forgetFact("Тарковского", true, token)
        assertTrue(done is MemoryOutcome.Forgotten)
        assertEquals(FactStatus.FORGOTTEN.name, dao.rows.values.first().status)
        // The row stays: forgetting is a status, not a delete (audit trail).
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `forget with no match says so`() = runTest {
        val (c, _) = coordinator()
        assertTrue(c.forgetFact("несуществующее", false, null) is MemoryOutcome.NothingToForget)
    }

    @Test
    fun `tool surface executes through ToolContract with honest JSON`() = runTest {
        val (c, _) = coordinator()
        val tools = c.tools().associateBy { it.name }
        assertEquals(setOf("remember_fact", "recall_facts", "forget_fact"), tools.keys)

        val written = tools["remember_fact"]!!.execute(
            """{"value":"зовут Алексей","category":"name"}""",
        )
        assertEquals("written", jsonKey(written, "outcome"))
        assertTrue(jsonKey(written, "spoken").isNotEmpty())

        val recalled = tools["recall_facts"]!!.execute("""{"query":"имя"}""")
        assertEquals("recalled", jsonKey(recalled, "outcome"))

        val candidates = tools["forget_fact"]!!.execute("""{"query":"Алексей"}""")
        assertEquals("confirm_forget", jsonKey(candidates, "outcome"))
        assertTrue(jsonKey(candidates, "confirmToken").isNotEmpty())
    }

    @Test
    fun `gather respects the disabled switch and the budget shape`() = runTest {
        val (c, dao) = coordinator()
        c.rememberFact("зовут Алексей", "name", null)
        c.rememberFact("любит Тарковского", "likes", null)
        val block = c.gather("кто я такой")
        assertTrue(block.contains("<memory-context>"))
        assertTrue(block.contains("зовут Алексей"))
        assertTrue(block.length <= 1400) // block + framing, well under the 1200 budget + wrapper

        memoryEnabled.value = false
        assertEquals("", c.gather("кто я такой"))
        memoryEnabled.value = true

        // Empty DB renders empty without touching anything.
        dao.rows.clear()
        assertEquals("", c.gather("кто я такой"))
    }

    private fun jsonKey(json: String, key: String): String {
        val obj = Json.parseToJsonElement(json).jsonObject
        return (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
    }
}
