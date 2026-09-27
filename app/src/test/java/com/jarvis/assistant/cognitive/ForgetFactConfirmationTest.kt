package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.model.FactStatus
import com.jarvis.assistant.cognitive.tools.MemoryOutcome
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * COGNITIVE_PLAN §6.4 (forget hardening): the coordinator-level contract of the
 * confirmation gate. Each of these must FAIL against the previous
 * one-turn-wide gate — an explicit affirmative is now required in the
 * IMMEDIATELY-NEXT user turn, bounded by turn age AND wall clock, and a
 * partial delete must never be reported (or consumed) as a success.
 */
class ForgetFactConfirmationTest {

    private val memoryEnabled = MutableStateFlow(true)
    private val autoExtract = MutableStateFlow(false)
    private val cloudEnabled = MutableStateFlow(true)
    private val sensitiveVisible = MutableStateFlow(true)

    private fun coordinator(
        factDao: UserFactDao = FakeUserFactDao(),
        nowMs: () -> Long = { 1_000L },
    ): CognitiveCoordinator {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return CognitiveCoordinator(
            deps = CognitiveDeps(
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
                nowMs = nowMs,
                cpuDispatcher = Dispatchers.Unconfined,
                cognitiveDispatcher = Dispatchers.Unconfined,
            ),
            parentScope = scope,
        )
    }

    /** Arm a listing in turn 1 for a single matching fact. */
    private suspend fun arm(c: CognitiveCoordinator, listingUtterance: String = "забудь Тарковского") {
        c.noteTurnStart(1)
        c.noteUserUtterance(1, listingUtterance)
        assertTrue(c.forgetFact("Тарковского", confirmed = false) is MemoryOutcome.ForgetCandidates)
    }

    @Test
    fun `a same-turn confirmation is still refused`() = runTest {
        val dao = FakeUserFactDao()
        val c = coordinator(dao)
        c.rememberFact("любит Тарковского", "likes", null)
        arm(c)

        val refused = c.forgetFact("Тарковского", confirmed = true)
        assertTrue(refused is MemoryOutcome.ForgetCandidates)
        assertEquals(FactStatus.ACTIVE.name, dao.rows.values.first().status)
    }

    @Test
    fun `a later non-affirmative turn is refused and re-listed`() = runTest {
        for (utterance in listOf("нет", "а что за фильм?", "расскажи о погоде")) {
            val dao = FakeUserFactDao()
            val c = coordinator(dao)
            c.rememberFact("любит Тарковского", "likes", null)
            arm(c)

            c.noteTurnStart(2)
            c.noteUserUtterance(2, utterance)
            val refused = c.forgetFact("Тарковского", confirmed = true)
            assertTrue(
                "«$utterance» must be refused, got $refused",
                refused is MemoryOutcome.ForgetCandidates,
            )
            assertEquals(
                "«$utterance» must not delete",
                FactStatus.ACTIVE.name,
                dao.rows.values.first().status,
            )
        }
    }

    @Test
    fun `an explicit affirmative in the immediately-next turn succeeds`() = runTest {
        for (utterance in listOf("да", "подтверждаю", "yes")) {
            val dao = FakeUserFactDao()
            val c = coordinator(dao)
            c.rememberFact("любит Тарковского", "likes", null)
            arm(c)

            c.noteTurnStart(2)
            c.noteUserUtterance(2, utterance)
            val done = c.forgetFact("Тарковского", confirmed = true)
            assertTrue("«$utterance» must confirm, got $done", done is MemoryOutcome.Forgotten)
            assertEquals(FactStatus.FORGOTTEN.name, dao.rows.values.first().status)
        }
    }

    @Test
    fun `a grant cannot be confirmed two user turns later`() = runTest {
        val dao = FakeUserFactDao()
        val c = coordinator(dao)
        c.rememberFact("любит Тарковского", "likes", null)
        arm(c)

        // Turn 2 passes without any confirmation attempt.
        c.noteTurnStart(2)
        c.noteUserUtterance(2, "какая погода")

        // Turn 3 says yes — but the turn-1 grant is superseded; only the
        // IMMEDIATELY-next user turn may confirm.
        c.noteTurnStart(3)
        c.noteUserUtterance(3, "да")
        val refused = c.forgetFact("Тарковского", confirmed = true)
        assertTrue("stale grant must be refused: $refused", refused is MemoryOutcome.ForgetCandidates)
        assertEquals(FactStatus.ACTIVE.name, dao.rows.values.first().status)
    }

    @Test
    fun `an expired wall-clock grant is refused, cleared and re-listed`() = runTest {
        val clock = AtomicLong(1_000L)
        val dao = FakeUserFactDao()
        val c = coordinator(dao, nowMs = { clock.get() })
        c.rememberFact("любит Тарковского", "likes", null)
        arm(c)

        // The immediately-next user turn arrives AFTER the wall-clock TTL.
        clock.set(1_000L + CognitiveCoordinator.FORGET_PENDING_TTL_MS + 1)
        c.noteTurnStart(2)
        c.noteUserUtterance(2, "да")
        val expired = c.forgetFact("Тарковского", confirmed = true)
        assertTrue("expired grant must be refused: $expired", expired is MemoryOutcome.ForgetCandidates)
        assertEquals(FactStatus.ACTIVE.name, dao.rows.values.first().status)

        // The expired record was cleared/superseded by the re-list: a fresh
        // immediate-next-turn yes now succeeds (the old grant never lingered).
        c.noteTurnStart(3)
        c.noteUserUtterance(3, "да")
        assertTrue(c.forgetFact("Тарковского", confirmed = true) is MemoryOutcome.Forgotten)
    }

    @Test
    fun `the same id set with a different query is refused`() = runTest {
        val dao = FakeUserFactDao()
        val c = coordinator(dao)
        c.rememberFact("любит Тарковского", "likes", null)
        arm(c)

        // "Тарковский" resolves to the SAME fact id set but is a different
        // query: the grant is bound to the query it was armed with.
        c.noteTurnStart(2)
        c.noteUserUtterance(2, "да")
        val refused = c.forgetFact("Тарковский", confirmed = true)
        assertTrue(refused is MemoryOutcome.ForgetCandidates)
        assertEquals(FactStatus.ACTIVE.name, dao.rows.values.first().status)
    }

    @Test
    fun `a mid-loop delete failure reports Failed and does not consume the confirmation`() = runTest {
        // The FIRST updateStatus of a two-fact loop throws, so nothing was
        // deleted and the candidate set is unchanged. Under the old ordering
        // (`set(null)` before the loop) the grant was already consumed, so a
        // same-turn retry was refused; now the grant survives and the retry
        // succeeds.
        val delegate = FakeUserFactDao()
        var calls = 0
        var failing = true
        val dao = object : UserFactDao by delegate {
            override suspend fun updateStatus(factId: String, status: String, now: Long) {
                calls++
                if (failing && calls == 1) error("SQL update bind failed for 'private'")
                delegate.updateStatus(factId, status, now)
            }
        }
        val c = coordinator(dao)
        c.rememberFact("любит Тарковского", "likes", null)
        c.rememberFact("смотрит Тарковского", "likes", null)
        arm(c)

        c.noteTurnStart(2)
        c.noteUserUtterance(2, "да")
        val failed = c.forgetFact("Тарковского", confirmed = true)
        assertTrue("must be Failed, got $failed", failed is MemoryOutcome.Failed)
        assertTrue(
            "nothing should have been deleted",
            delegate.rows.values.all { it.status == FactStatus.ACTIVE.name },
        )

        // The grant was NOT consumed: the retry in the SAME confirming turn
        // (the tool loop's next pass) still carries the user's consent.
        failing = false
        val retried = c.forgetFact("Тарковского", confirmed = true)
        assertTrue("retry must succeed, got $retried", retried is MemoryOutcome.Forgotten)
        assertEquals(2, delegate.rows.values.count { it.status == FactStatus.FORGOTTEN.name })
    }

    @Test
    fun `a partial delete never claims success and cannot double-delete`() = runTest {
        // The SECOND updateStatus throws: one fact is forgotten, one survives.
        val delegate = FakeUserFactDao()
        var calls = 0
        val dao = object : UserFactDao by delegate {
            override suspend fun updateStatus(factId: String, status: String, now: Long) {
                calls++
                if (calls == 2) error("SQL update bind failed for 'private'")
                delegate.updateStatus(factId, status, now)
            }
        }
        val c = coordinator(dao)
        c.rememberFact("любит Тарковского", "likes", null)
        c.rememberFact("смотрит Тарковского", "likes", null)
        arm(c)

        c.noteTurnStart(2)
        c.noteUserUtterance(2, "да")
        val outcome = c.forgetFact("Тарковского", confirmed = true)
        assertTrue("partial delete must be Failed, got $outcome", outcome is MemoryOutcome.Failed)
        assertEquals(1, delegate.rows.values.count { it.status == FactStatus.FORGOTTEN.name })
        assertTrue(
            "at least one fact must remain ACTIVE",
            delegate.rows.values.any { it.status == FactStatus.ACTIVE.name },
        )

        // The candidate set has changed, so a later confirmation is refused
        // and the survivor stays — a stale grant can never double-delete.
        c.noteTurnStart(3)
        c.noteUserUtterance(3, "да")
        assertTrue(c.forgetFact("Тарковского", confirmed = true) is MemoryOutcome.ForgetCandidates)
        assertTrue(delegate.rows.values.any { it.status == FactStatus.ACTIVE.name })
        assertEquals(1, delegate.rows.values.count { it.status == FactStatus.FORGOTTEN.name })
    }
}
