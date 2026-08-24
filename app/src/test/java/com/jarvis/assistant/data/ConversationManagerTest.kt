package com.jarvis.assistant.data

import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.ToolCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class ConversationManagerTest {
    private lateinit var fakeDao: FakeMessageDao
    private lateinit var manager: ConversationManager

    @Before fun setup() {
        fakeDao = FakeMessageDao()
        manager = ConversationManager(fakeDao)
    }

    @Test fun `insert and retrieve history`() = runBlocking {
        manager.addMessage(Message(role = "user", content = "Hello"))
        manager.addMessage(Message(role = "assistant", content = "Hi there"))
        val history = manager.getHistoryForLLM()
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("assistant", history[1].role)
    }

    @Test fun `history capped at max messages`() = runBlocking {
        for (i in 1..25) manager.addMessage(Message(role = "user", content = "msg $i"))
        val history = manager.getHistoryForLLM()
        assertEquals(20, history.size)
    }

    @Test fun `tool call roundtrip via persistence`() = runBlocking {
        val call = ToolCall(id = "call_1", function = FunctionCall("getWeather", """{"location":"Moscow"}"""))
        val assistantMsg = Message(role = "assistant", content = "", toolCalls = listOf(call))
        manager.addMessage(assistantMsg)
        val history = manager.getHistoryForLLM()
        assertEquals(1, history.size)
        assertEquals(1, history[0].toolCalls?.size)
        assertEquals("call_1", history[0].toolCalls!![0].id)
        assertEquals("getWeather", history[0].toolCalls!![0].function.name)
    }

    @Test fun `tool result with toolCallId`() = runBlocking {
        manager.addMessage(Message(role = "assistant", content = "", toolCalls = listOf(ToolCall("call_1", function = FunctionCall("getWeather", "{}")))))
        manager.addMessage(Message(role = "tool", content = """{"temp":21}""", toolCallId = "call_1", name = "getWeather"))
        val history = manager.getHistoryForLLM()
        assertEquals(2, history.size)
        assertEquals("tool", history[1].role)
        assertEquals("call_1", history[1].toolCallId)
    }

    @Test fun `trim preserves tool results with kept assistants`() = runBlocking {
        // Insert 25 messages: user+assistant pairs, with tool calls on some
        for (i in 1..10) {
            manager.addMessage(Message(role = "user", content = "u$i"))
            if (i == 8) {
                manager.addMessage(Message(role = "assistant", content = "", toolCalls = listOf(ToolCall("tc_$i", function = FunctionCall("fn", "{}")))))
                manager.addMessage(Message(role = "tool", content = "result $i", toolCallId = "tc_$i", name = "fn"))
            } else {
                manager.addMessage(Message(role = "assistant", content = "a$i"))
            }
        }
        // Trim should keep the tool results that belong to kept assistants
        val history = manager.getHistoryForLLM()
        assertTrue(history.any { it.role == "tool" && it.toolCallId == "tc_8" })
    }

    @Test fun `name field persists`() = runBlocking {
        manager.addMessage(Message(role = "tool", content = "result", toolCallId = "c1", name = "getWeather"))
        val history = manager.getHistoryForLLM()
        assertEquals("getWeather", history[0].name)
    }
}

/**
 * In-memory fake [MessageDao] for unit testing [ConversationManager] without
 * Robolectric or an Android instrumentation context.
 */
private class FakeMessageDao : MessageDao {
    private val store = mutableListOf<MessageEntity>()
    private val idGen = AtomicLong(1)

    override suspend fun insert(e: MessageEntity) {
        store.add(e.copy(id = idGen.getAndIncrement()))
    }

    override suspend fun all(): List<MessageEntity> = store.toList()

    override suspend fun recentDesc(n: Int): List<MessageEntity> =
        store.takeLast(n).reversed()

    override suspend fun trimTo(n: Int) {
        if (store.size <= n) return
        store.removeAll(store.take(store.size - n).toSet())
    }

    override suspend fun clear() { store.clear() }

    override suspend fun trimToIds(ids: Set<Long>) {
        store.removeAll { it.id !in ids }
    }
}
