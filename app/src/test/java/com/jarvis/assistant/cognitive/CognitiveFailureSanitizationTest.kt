package com.jarvis.assistant.cognitive

import com.jarvis.assistant.cognitive.data.UserFactDao
import com.jarvis.assistant.cognitive.data.UserFactEntity
import com.jarvis.assistant.cognitive.extract.FakeExtractionQueueDao
import com.jarvis.assistant.cognitive.extract.FakeMemoryMetaDao
import com.jarvis.assistant.cognitive.extract.FakeMessageDao
import com.jarvis.assistant.cognitive.extract.FakeUserFactDao
import com.jarvis.assistant.cognitive.tools.MemoryOutcome
import com.jarvis.assistant.cognitive.tools.toJson
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber

/**
 * P1 review pin: the catch-alls around the Room DAOs on the memory-tool
 * surface must never let an exception MESSAGE cross into user-visible or
 * egress paths. Room/SQLite messages quote the offending row values
 * (`near 'SECRET'`), kotlinx.serialization quotes the offending literal,
 * and [MemoryOutcome.Failed.detail] flows to BOTH the LLM tool-result JSON
 * (network egress) and the spoken failure string (memoryWriteFailed
 * interpolates it) — while the throwable, if it rides a persisted log line,
 * writes the same bytes to the rotating file log (LogScrubber deliberately
 * does not match quoted spans). Only the exception CLASS may cross; stacks
 * stay DEBUG, which FileLoggingTree does not persist.
 *
 * The sentinel below plays the role of a fact value that broke a DAO call.
 */
class CognitiveFailureSanitizationTest {

    private val secret = "SECRET-MEMO-CONTENT"

    private val memoryEnabled = MutableStateFlow(true)
    private val autoExtract = MutableStateFlow(false)
    private val cloudEnabled = MutableStateFlow(true)
    private val sensitiveVisible = MutableStateFlow(true)

    /**
     * DAO whose configured operations fail the way Room does: message quotes
     * the value. FakeUserFactDao is final, so the fake is composed by
     * delegation and only the three boomerang methods shadow it.
     */
    private class BoomingFactDao(
        private val boomOnInsert: Boolean = false,
        private val boomOnActiveFacts: Boolean = false,
        private val boomOnUpdateStatus: Boolean = false,
        private val secret: String,
        private val fake: FakeUserFactDao = FakeUserFactDao(),
    ) : UserFactDao by fake {
        override suspend fun insert(fact: UserFactEntity): Long {
            if (boomOnInsert) {
                // Room quotes the offending value in the message.
                error(
                    "SQLiteConstraintException: UNIQUE constraint failed, " +
                        "near '$secret'",
                )
            }
            return fake.insert(fact)
        }

        override suspend fun activeFacts(): List<UserFactEntity> {
            if (boomOnActiveFacts) {
                error("SQLiteQuery: no such column, query selected '$secret'")
            }
            return fake.activeFacts()
        }

        override suspend fun updateStatus(factId: String, status: String, now: Long) {
            if (boomOnUpdateStatus) {
                error("SQL update bind failed for '$secret'")
            }
            fake.updateStatus(factId, status, now)
        }
    }

    private fun coordinator(factDao: UserFactDao): CognitiveCoordinator {
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
                nowMs = { 1_000L },
            ),
            parentScope = scope,
        )
    }

    // --- log capture (mirrors SpeechContentLoggingTest's CapturingTree, but
    // keeps the throwable so we can pin that none rides INFO+) ---

    private data class Entry(val priority: Int, val message: String, val throwable: Throwable?)

    // android.util.Log.DEBUG/INFO constants are not resolvable in pure-JVM unit
    // tests (android.jar stubs throw), so the two priorities used below are
    // pinned numerically, mirroring SpeechContentLoggingTest.
    private val priInfo = 4

    private inner class CapturingTree : Timber.Tree() {
        val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries.add(Entry(priority, message, t))
        }
    }

    private lateinit var tree: CapturingTree

    @Before
    fun plantCapturingTree() {
        Timber.uprootAll()
        tree = CapturingTree()
        Timber.plant(tree)
    }

    @After
    fun uprootCapturingTree() {
        Timber.uprootAll()
    }

    /** Anything at INFO+ (FileLoggingTree's persistence floor) containing the sentinel — leak. */
    private fun persistedLeaks(): List<Entry> = tree.entries.filter {
        it.priority >= priInfo &&
            (it.message.contains(secret) || it.throwable?.let { t -> t.stackTraceToString().contains(secret) } == true)
    }

    private fun assertSanitized(outcome: MemoryOutcome) {
        assertTrue("expected Failed, got $outcome", outcome is MemoryOutcome.Failed)
        val detail = (outcome as MemoryOutcome.Failed).detail.orEmpty()
        assertFalse("exception message crossed into detail: $detail", detail.contains(secret))
        val json = outcome.toJson().toString()
        assertFalse("sentinel crossed into the LLM tool JSON: $json", json.contains(secret))
        // The spoken string interpolates detail (memoryWriteFailed) — the only
        // user-hearable failure surface. It must stay sentinel-free too.
        val spoken = ToolStrings.Default.memoryWriteFailed(detail.ifEmpty { null })
        assertFalse("sentinel reached the spoken failure: $spoken", spoken.contains(secret))
        assertEquals("INFO+ log leak: ${persistedLeaks()}", emptyList<Entry>(), persistedLeaks())
    }

    @Test
    fun `rememberFact write failure carries only the exception class`() = runTest {
        val c = coordinator(BoomingFactDao(boomOnInsert = true, secret = secret))
        assertSanitized(c.rememberFact("любимое блюдо — $secret", "food", null))
    }

    @Test
    fun `recallFacts query failure carries only the exception class`() = runTest {
        val c = coordinator(BoomingFactDao(boomOnActiveFacts = true, secret = secret))
        assertSanitized(c.recallFacts("anything"))
    }

    @Test
    fun `forgetFact delete failure carries only the exception class`() = runTest {
        // Clean insert, honest candidate step, then the FORGOTTEN update blows
        // up quoting the row value — the exact SQLiteConstraintException shape.
        val dao = BoomingFactDao(boomOnUpdateStatus = true, secret = secret)
        val c = coordinator(dao)
        c.rememberFact("аллергия на $secret", "health", null)
        val candidates = c.forgetFact(secret, confirmed = false, token = null)
        assertTrue("two-step confirm must still work: $candidates", candidates is MemoryOutcome.ForgetCandidates)
        val token = (candidates as MemoryOutcome.ForgetCandidates).confirmToken
        assertSanitized(c.forgetFact(secret, confirmed = true, token = token))
    }

    @Test
    fun `cancellation still propagates through the guarded catch-alls`() = runTest {
        // The sanitization edits must not swallow CancellationException (AGENTS
        // cognitive contract): the DAO throws CE and rememberFact must rethrow.
        val dao = object : UserFactDao by FakeUserFactDao() {
            override suspend fun insert(fact: UserFactEntity): Long =
                throw kotlinx.coroutines.CancellationException("cancel me")
        }
        val c = coordinator(dao)
        val failed = runCatching { c.rememberFact("зовут Алексей", "name", null) }
        assertTrue(
            "CancellationException must be rethrown, not turned into Failed",
            failed.exceptionOrNull() is kotlinx.coroutines.CancellationException,
        )
    }
}
