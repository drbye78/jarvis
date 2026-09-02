package com.jarvis.assistant

import com.jarvis.assistant.data.ConversationManager
import com.jarvis.assistant.data.MessageDao
import com.jarvis.assistant.data.MessageEntity
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.FunctionCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory fake DAO so ConversationManager logic runs on the JVM. */
class FakeMessageDao : MessageDao {
    val rows = mutableListOf<MessageEntity>()
    private var nextId = 1L

    override suspend fun insert(e: MessageEntity): Long {
        val id = nextId++
        rows.add(e.copy(id = id))
        return id
    }

    override suspend fun insertAll(e: List<MessageEntity>): List<Long> = e.map { insert(it) }

    override suspend fun all(): List<MessageEntity> = rows.sortedBy { it.id }

    override suspend fun recentDesc(n: Int): List<MessageEntity> =
        rows.sortedByDescending { it.id }.take(n)

    override fun recentDescLive(n: Int): kotlinx.coroutines.flow.Flow<List<MessageEntity>> =
        kotlinx.coroutines.flow.MutableStateFlow(rows.sortedByDescending { it.id }.take(n))

    override suspend fun trimToIds(ids: Set<Long>) {
        rows.removeAll { it.id !in ids }
    }

    override suspend fun deleteAllExceptRecent(maxMessages: Int) {
        val toKeep = rows.sortedByDescending { it.id }.take(maxMessages).map { it.id }.toSet()
        rows.removeAll { it.id !in toKeep }
    }

    override suspend fun clear() {
        rows.clear()
    }
}

class ConversationManagerTest {

    private fun toolCallJson(id: String) =
        """[{"id":"$id","type":"function","function":{"name":"setAlarm","arguments":"{}"}}]"""

    @Test
    fun `ordering is by id not timestamp`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        // Same-millisecond inserts (classic ambiguity bug):
        cm.addMessage(Message(role = "user", content = "first"))
        cm.addMessage(Message(role = "assistant", content = "second"))
        val history = cm.getHistoryForLLM()
        assertEquals(listOf("first", "second"), history.map { it.content })
    }

    @Test
    fun `trim keeps assistant and its tool results together`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 3)
        // 6 messages: u a(with tool) tool u a(with tool) tool
        cm.addMessage(Message(role = "user", content = "u1"))
        cm.addMessage(
            Message(
                role = "assistant", content = "a1",
                toolCalls = listOf(ToolCall("t1", function = FunctionCall("setAlarm", "{}"))),
            )
        )
        cm.addMessage(Message(role = "tool", content = "r1", toolCallId = "t1"))
        cm.addMessage(Message(role = "user", content = "u2"))
        cm.addMessage(
            Message(
                role = "assistant", content = "a2",
                toolCalls = listOf(ToolCall("t2", function = FunctionCall("getWeather", "{}"))),
            )
        )
        cm.addMessage(Message(role = "tool", content = "r2", toolCallId = "t2"))

        val history = cm.getHistoryForLLM()
        // The kept window must not orphan any tool message at the head.
        assertTrue(history.first().role != "tool")
        // And every tool result inside must follow its assistant message.
        val toolIdx = history.indexOfFirst { it.role == "tool" }
        assertTrue(toolIdx > 0)
        assertEquals("assistant", history[toolIdx - 1].role)
        assertEquals(listOf("t2"), history[toolIdx].let { listOf(it.toolCallId) })
    }

    @Test
    fun `leading orphan tool messages are dropped on retrieval`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        // Simulate a corrupted/legacy DB state: tool message with no parent.
        cm.addMessage(Message(role = "tool", content = "orphan", toolCallId = "gone"))
        cm.addMessage(Message(role = "user", content = "hi"))
        cm.addMessage(Message(role = "assistant", content = "hello"))

        val history = cm.getHistoryForLLM()
        assertEquals("user", history.first().role)
        assertEquals(2, history.size)
    }

    @Test
    fun `tool calls round trip through persistence`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        val call = ToolCall("t9", function = FunctionCall("setTimer", """{"minutes":5}"""))
        // Persisted as a real pair: the sanitizer (correctly) clears ids that
        // have no tool result in the window.
        cm.addAssistantWithToolResults(
            assistant = Message(role = "assistant", content = "", toolCalls = listOf(call)),
            results = listOf(Message(role = "tool", content = """{"status":"started"}""", toolCallId = "t9")),
        )
        val history = cm.getHistoryForLLM()
        val restored = history.single { it.role == "assistant" }
        assertEquals(listOf(call), restored.toolCalls)
        assertEquals("t9", history.last { it.role == "tool" }.toolCallId)
    }

    @Test
    fun `mid-window dangling assistant tool calls are cleared`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        cm.addMessage(Message(role = "user", content = "u1"))
        cm.addMessage(
            Message(
                role = "assistant", content = "partial answer",
                toolCalls = listOf(ToolCall("tX", function = FunctionCall("setAlarm", "{}"))),
            )
        )
        cm.addMessage(Message(role = "user", content = "u2"))

        val history = cm.getHistoryForLLM()
        assertEquals(3, history.size) // row kept, not dropped
        val assistant = history[1]
        assertEquals("assistant", assistant.role)
        assertEquals(null, assistant.toolCalls) // unpaired ids cleared
        assertEquals("partial answer", assistant.content) // content preserved
    }

    @Test
    fun `mid-window orphan tool row is dropped`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        cm.addMessage(Message(role = "user", content = "u1"))
        cm.addMessage(Message(role = "tool", content = "orphan", toolCallId = "gone"))
        cm.addMessage(Message(role = "assistant", content = "hello"))

        val history = cm.getHistoryForLLM()
        assertEquals(2, history.size)
        assertTrue(history.none { it.role == "tool" })
        assertEquals(listOf("user", "assistant"), history.map { it.role })
    }

    @Test
    fun `valid assistant tool pairs survive sanitization`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        val call = ToolCall("t1", function = FunctionCall("setAlarm", "{}"))
        cm.addMessage(Message(role = "user", content = "u1"))
        cm.addAssistantWithToolResults(
            assistant = Message(role = "assistant", content = "", toolCalls = listOf(call)),
            results = listOf(Message(role = "tool", content = "ok", toolCallId = "t1")),
        )
        cm.addMessage(Message(role = "user", content = "u2"))

        val history = cm.getHistoryForLLM()
        assertEquals(4, history.size)
        assertEquals(listOf(call), history[1].toolCalls)
        assertEquals("t1", history[2].toolCallId)
        assertEquals("assistant", history[1].role) // tool follows its assistant
    }

    @Test
    fun `assistant keeps only tool call ids that have results`() = runBlocking {
        val dao = FakeMessageDao()
        val cm = ConversationManager(dao, maxMessages = 20)
        val paired = ToolCall("p1", function = FunctionCall("setTimer", "{}"))
        val dangling = ToolCall("d1", function = FunctionCall("setAlarm", "{}"))
        cm.addAssistantWithToolResults(
            assistant = Message(
                role = "assistant", content = "",
                toolCalls = listOf(paired, dangling),
            ),
            results = listOf(Message(role = "tool", content = "ok", toolCallId = "p1")),
        )

        val history = cm.getHistoryForLLM()
        assertEquals(listOf(paired), history.single { it.role == "assistant" }.toolCalls)
    }
}
