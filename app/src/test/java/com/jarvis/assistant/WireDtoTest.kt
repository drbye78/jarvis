package com.jarvis.assistant

import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.Message
import com.jarvis.assistant.model.ToolCall
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.wire.toWire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE regression test for the original critical bug #1: tool-call messages
 * must serialize with snake_case wire names (`tool_calls`, `tool_call_id`).
 * The v2.x app serialized camelCase and every second LLM request failed.
 */
class WireDtoTest {

    private val json = Json { encodeDefaults = false }

    @Test
    fun `assistant tool_calls message uses snake_case on the wire`() {
        val msg = Message(
            role = "assistant",
            content = "",
            toolCalls = listOf(
                ToolCall(id = "call_1", function = FunctionCall("setAlarm", """{"time":"07:30"}"""))
            ),
        )
        val wire = json.encodeToString(com.jarvis.assistant.wire.WireMessage.serializer(), msg.toWire())
        assertTrue(wire.contains("\"tool_calls\""))
        assertTrue(!wire.contains("\"toolCalls\""))
        // id / type / function names preserved
        assertTrue(wire.contains("\"call_1\""))
        assertTrue(wire.contains("\"setAlarm\""))
    }

    @Test
    fun `tool result message carries tool_call_id`() {
        val msg = Message(role = "tool", content = "ok", toolCallId = "call_42", name = "getWeather")
        val wire = json.encodeToString(com.jarvis.assistant.wire.WireMessage.serializer(), msg.toWire())
        assertTrue(wire.contains("\"tool_call_id\""))
        assertTrue(wire.contains("\"call_42\""))
        assertTrue(!wire.contains("\"toolCallId\""))
    }

    @Test
    fun `chat request wire format`() {
        val request = ChatRequest(
            messages = listOf(Message.user("привет")),
            tools = listOf(
                ToolDefinition(
                    name = "getWeather",
                    description = "weather",
                    parameters = buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                    },
                )
            ),
            model = null,
            temperature = 0.5,
            maxTokens = 128,
        )
        val wire = request.toWire()
        val encoded = json.encodeToString(
            com.jarvis.assistant.wire.WireChatRequest.serializer(), wire
        )
        // max_tokens (not maxTokens), tools present, stream=true
        assertTrue(encoded.contains("\"max_tokens\""))
        assertTrue(!encoded.contains("\"maxTokens\""))
        assertTrue(encoded.contains("\"tools\""))
        assertTrue(encoded.contains("\"stream\""))
        // parameters must be a JSON OBJECT on the wire, not a string
        val parsed = json.parseToJsonElement(encoded).jsonObject
        val tool = parsed["tools"]!!.jsonArray[0].jsonObject
        val fn = tool["function"]!!.jsonObject
        assertTrue(fn["parameters"] is kotlinx.serialization.json.JsonObject)
    }

    @Test
    fun `blank content serializes to null`() {
        val msg = Message(role = "assistant", content = "")
        val wire = msg.toWire()
        assertNull(wire.content)
    }
}
