package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.CognitiveCoordinator
import com.jarvis.assistant.cognitive.extract.FakeBehaviorLogDao
import com.jarvis.assistant.cognitive.extract.FakeCommandEventDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeHabitRuleDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §8: the coordinator's behaviour plumbing — telemetry write,
 * every-10th recompute, the accept/reject reinforcement loop, and the
 * arbiter's FIRED path end-to-end (all gates green → template spoken →
 * FIRED row + rule bookkeeping).
 */
class CognitiveBehaviorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private class Fx(now: Long) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val llm = object : com.jarvis.assistant.llm.LlmClient {
            override fun chatStream(request: com.jarvis.assistant.model.ChatRequest) =
                throw UnsupportedOperationException("behaviour tests never reach the LLM")
        }
        val factDao = FakeUserFactDao()
        val queueDao = FakeExtractionQueueDao()
        val metaDao = FakeMemoryMetaDao()
        val messageDao = FakeMessageDao()
        val eventDao = FakeCommandEventDao()
        val ruleDao = FakeHabitRuleDao()
        val logDao = FakeBehaviorLogDao()
        val summaryDao = com.jarvis.assistant.cognitive.extract.FakeSessionSummaryDao()
        val memoryEnabled = MutableStateFlow(true)
        val autoExtract = MutableStateFlow(false)
        val cloudEnabled = MutableStateFlow(false)
        val sensitive = MutableStateFlow(true)
        val behaviorEnabled = MutableStateFlow(true)
        val quietStart = MutableStateFlow(23)
        val quietEnd = MutableStateFlow(8)
        val quota = MutableStateFlow(2)
        val sessionIdle = MutableStateFlow(true)
        val spoken = mutableListOf<String>()
        var now: Long = now
        var hour: Int = 20

        val lastInteraction = now - 60 * 60_000L // one hour ago — presence OK

        val coordinator = CognitiveCoordinator(
            factDao = factDao,
            queueDao = queueDao,
            metaDao = metaDao,
            messageDao = messageDao,
            llm = llm,
            memoryEnabled = memoryEnabled,
            autoExtractEnabled = autoExtract,
            cloudEnabled = cloudEnabled,
            sensitiveVisible = sensitive,
            eventDao = eventDao,
            ruleDao = ruleDao,
            behaviorLogDao = logDao,
            summaryDao = summaryDao,
            behaviorEnabled = behaviorEnabled,
            behaviorQuietStart = quietStart,
            behaviorQuietEnd = quietEnd,
            behaviorDailyQuota = quota,
            sessionIdle = sessionIdle,
            lastInteractionAt = { lastInteraction },
            speaker = { text -> spoken.add(text); true },
            habitEligibleTools = setOf("playMusic", "getWeather"),
            modelId = { "test-model" },
            hourOfDay = { hour },
            strings = ToolStrings.Default,
            parentScope = scope,
            nowMs = { now },
        )

        /** Polls until [condition] or timeout — fire-and-forget launches. */
        suspend fun await(condition: () -> Boolean) {
            withTimeout(5_000) {
                while (!condition()) delay(10)
            }
        }
    }

    private fun rule() = HabitRuleEntity(
        id = 0,
        kind = HabitRuleEntity.KIND_TIME_WINDOW,
        tool = "playMusic",
        argsFingerprint = "q:джаз",
        hourBucket = 10,
        daySet = null,
        supportCount = 9,
        state = HabitRuleEntity.STATE_ACTIVE,
        acceptCount = 0,
        rejectCount = 0,
        lastSuggestedAt = null,
        lastFiredAt = null,
        mutedUntil = null,
        createdAt = 0,
    )

    private fun now() = 6L * 24 * 60 * 60_000L + 20L * 60 * 60_000L

    @Test
    fun `recordCommandEvent stores the fingerprint row`() = runBlocking {
        val fx = Fx(now())
        fx.coordinator.recordCommandEvent("playMusic", """{"query":"Джаз"}""", ok = true, latencyMs = 42)
        val row = fx.eventDao.rows.single()
        assertEquals("playMusic", row.tool)
        assertEquals("q:джаз", row.argsFingerprint)
        assertTrue(row.ok)
        assertTrue(row.origin == CommandEventEntity.ORIGIN_VOICE)
    }

    @Test
    fun `every tenth event triggers a habit recompute`() = runBlocking {
        val fx = Fx(now())
        // 9 pre-seeded events; the 10th comes through the recorder.
        repeat(9) {
            fx.eventDao.rows += CommandEventEntity(
                at = fx.now - it * 60_000L,
                tool = "playMusic",
                argsFingerprint = "q:джаз",
                ok = true,
                latencyMs = 10,
                origin = CommandEventEntity.ORIGIN_VOICE,
            )
        }
        fx.coordinator.recordCommandEvent("playMusic", """{"query":"Джаз"}""", ok = true, latencyMs = 10)
        fx.await { fx.ruleDao.rows.isNotEmpty() }
        val rule = fx.ruleDao.rows.values.single()
        assertEquals(HabitRuleEntity.STATE_PROBATION, rule.state)
    }

    @Test
    fun `a matching command within the window reinforces the suggestion`() = runBlocking {
        val fx = Fx(now())
        val planted = rule().copy(lastFiredAt = fx.now - 5 * 60_000L) // 5 min ago
        fx.ruleDao.insert(planted)

        fx.coordinator.recordCommandEvent("playMusic", """{"query":"Джаз"}""", ok = true, latencyMs = 10)
        fx.await { fx.ruleDao.rows.values.single().acceptCount == 1 }
        val rule = fx.ruleDao.rows.values.single()
        assertEquals(HabitRuleEntity.STATE_ACTIVE, rule.state)
    }

    @Test
    fun `all gates green fires the deterministic proposal`() = runBlocking {
        val fx = Fx(now())
        fx.ruleDao.insert(rule())
        fx.coordinator.evaluateDueRules()
        fx.await { fx.spoken.isNotEmpty() }
        assertEquals("Ты обычно слушаешь «джаз» в это время. Включить?", fx.spoken.single())
        assertEquals(1, fx.logDao.rows.count { it.decision == "FIRED" })
        assertNotNull(fx.ruleDao.rows.values.single().lastFiredAt)
    }

    @Test
    fun `the daily quota caps FIRED decisions`() = runBlocking {
        val fx = Fx(now())
        fx.quota.value = 1
        fx.ruleDao.insert(rule())
        fx.ruleDao.insert(rule().copy(id = 0, argsFingerprint = "q:rock"))
        fx.coordinator.evaluateDueRules()
        fx.await { fx.logDao.rows.count { it.decision == "FIRED" } == 1 }
        // Give the loop a beat — no second FIRED may appear (quota 1).
        delay(100)
        assertEquals(1, fx.logDao.rows.count { it.decision == "FIRED" })
    }

    @Test
    fun `quiet hours block with a throttled audit row`() = runBlocking {
        val fx = Fx(now())
        fx.hour = 23 // inside the default 23→8 window
        fx.ruleDao.insert(rule())
        fx.coordinator.evaluateDueRules()
        fx.await { fx.logDao.rows.isNotEmpty() }
        val row = fx.logDao.rows.single()
        assertEquals("BLOCKED", row.decision)
        assertEquals("quiet_hours", row.reason)
        assertTrue(fx.spoken.isEmpty())
        // Throttled: an immediate re-evaluation writes nothing new.
        fx.coordinator.evaluateDueRules()
        assertEquals(1, fx.logDao.rows.size)
    }

    @Test
    fun `switch off means evaluateDueRules is a no-op`() = runBlocking {
        val fx = Fx(now())
        fx.behaviorEnabled.value = false
        fx.ruleDao.insert(rule())
        fx.coordinator.evaluateDueRules()
        delay(50)
        assertTrue(fx.logDao.rows.isEmpty())
        assertTrue(fx.spoken.isEmpty())
    }

    @Test
    fun `three explicit rejections mute the rule for 30 days`() = runBlocking {
        val fx = Fx(now())
        var planted = rule()
        planted = fx.ruleDao.insert(planted).let { fx.ruleDao.rows.values.single() }
        fx.logDao.rows += com.jarvis.assistant.cognitive.data.BehaviorLogEntity(
            at = fx.now,
            ruleId = planted.id,
            decision = "FIRED",
            reason = "all_gates_passed",
            utterance = "Ты обычно слушаешь «джаз» в это время. Включить?",
        )

        fx.coordinator.onFollowUpUtterance("Нет")
        fx.coordinator.onFollowUpUtterance("не надо")
        fx.coordinator.onFollowUpUtterance("не нужно")
        fx.await { fx.ruleDao.rows.values.single().state == HabitRuleEntity.STATE_MUTED }
        val muted = fx.ruleDao.rows.values.single()
        assertEquals(3, muted.rejectCount)
        assertNotNull(muted.mutedUntil)
    }

    @Test
    fun `a refusal with a continuation tail is engagement, not a reject`() = runBlocking {
        val fx = Fx(now())
        val planted = fx.ruleDao.insert(rule()).let { fx.ruleDao.rows.values.single() }
        fx.logDao.rows += com.jarvis.assistant.cognitive.data.BehaviorLogEntity(
            at = fx.now,
            ruleId = planted.id,
            decision = "FIRED",
            reason = "all_gates_passed",
            utterance = "…",
        )
        fx.coordinator.onFollowUpUtterance("нет, а что за трек?")
        delay(100)
        assertEquals(0, fx.ruleDao.rows.values.single().rejectCount)
    }

    @Test
    fun `wipeAll clears the behaviour tables too`() = runBlocking {
        val fx = Fx(now())
        fx.eventDao.rows += CommandEventEntity(
            at = fx.now, tool = "playMusic", argsFingerprint = "q:x", ok = true, latencyMs = 1,
            origin = CommandEventEntity.ORIGIN_VOICE,
        )
        fx.ruleDao.insert(rule())
        fx.coordinator.wipeAll()
        assertEquals("eventDao", 0, fx.eventDao.rows.size)
        assertEquals("ruleDao", 0, fx.ruleDao.rows.size)
    }
}
