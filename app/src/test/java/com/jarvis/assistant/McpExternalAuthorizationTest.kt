package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpAccess
import com.jarvis.assistant.mcp.McpCall2
import com.jarvis.assistant.mcp.McpClient
import com.jarvis.assistant.mcp.McpToolContract
import com.jarvis.assistant.mcp.McpToolDescriptor
import com.jarvis.assistant.mcp.McpToolResult2
import com.jarvis.assistant.mcp.McpToolsCall
import com.jarvis.assistant.mcp.McpToolsPage
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.session.TurnOrigin
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.TurnAuthorization
import com.jarvis.assistant.tools.WriteConfirmation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end enforcement for the untrusted EXTERNAL (MCP) tool class.
 *
 * This is the REAL path, not the pure policy: an [McpToolContract] over a fake
 * client is projected through [ToolRegistry]'s dynamic supplier and executed
 * through `executeResult`. The point is that on every denied turn the tool NEVER
 * runs — the fake client's `callTool` is not invoked — not merely that
 * `decide(...)` says no.
 */
class McpExternalAuthorizationTest {

    private class FakeMcpClient : McpClient {
        var callToolInvocations = 0

        override suspend fun initialize(): McpCall2 = McpCall2.Ok(McpToolResult2("", false))

        override suspend fun listTools(): McpToolsCall =
            McpToolsCall.Ok(McpToolsPage(emptyList(), nextCursor = null))

        override suspend fun callTool(name: String, argumentsJson: String): McpCall2 {
            callToolInvocations++
            return McpCall2.Ok(McpToolResult2("""{"ok":true}""", isError = false))
        }

        override suspend fun close() = Unit
    }

    private fun contract(
        client: McpClient,
        access: McpAccess = McpAccess.READ,
        toolName: String = "search",
    ): McpToolContract = McpToolContract(
        serverId = "srv-1",
        descriptor = McpToolDescriptor(toolName, "a tool", """{"type":"object","properties":{}}"""),
        client = client,
        access = access,
    )

    @Test
    fun `external mcp tool runs only on a bound voice turn`() = runBlocking {
        val client = FakeMcpClient()
        val tool = contract(client)
        val registry = ToolRegistry(tools = emptyList(), dynamicTools = { listOf(tool) })

        assertTrue(
            "the dynamic supplier must reach the advertised surface",
            registry.getToolDefinitions().any { it.name == tool.name },
        )

        // VOICE → allowed, and the external server is actually called.
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        val voice = registry.executeResult(FunctionCall(tool.name, "{}"))
        assertFalse("a voice turn must allow the external tool", voice.isError)
        assertEquals("an allowed call must reach the server", 1, client.callToolInvocations)

        // SYSTEM (SCHEDULED) → denied, server untouched.
        client.callToolInvocations = 0
        registry.setAuthorizationContext(1, TurnAuthorization.system())
        val system = registry.executeResult(FunctionCall(tool.name, "{}"))
        assertTrue("a system turn must deny the external tool", system.isError)
        assertEquals("a denied call must never reach the server", 0, client.callToolInvocations)

        // PROACTIVE → denied, server untouched.
        registry.setAuthorizationContext(1, TurnAuthorization(TurnOrigin.PROACTIVE, explicitUserCommand = false))
        val proactive = registry.executeResult(FunctionCall(tool.name, "{}"))
        assertTrue("a proactive turn must deny the external tool", proactive.isError)
        assertEquals("a denied call must never reach the server", 0, client.callToolInvocations)

        // No bound turn → denied, server untouched.
        registry.setAuthorizationContext(1, null)
        val offTurn = registry.executeResult(FunctionCall(tool.name, "{}"))
        assertTrue("an off-turn call must deny the external tool", offTurn.isError)
        assertEquals("a denied call must never reach the server", 0, client.callToolInvocations)
    }

    @Test
    fun `external write needs an affirmative on the immediately-next turn, single use`() = runBlocking {
        val client = FakeMcpClient()
        val tool = contract(client, access = McpAccess.WRITE, toolName = "delete_task")
        val store = WriteConfirmation()
        val registry = ToolRegistry(
            tools = emptyList(),
            dynamicTools = { listOf(tool) },
            writeConfirmation = store,
        )
        val call = FunctionCall(tool.name, """{"id":"42"}""")

        // Turn 1: the user asks; the model requests the write. No execution.
        store.noteTurnStart(1)
        store.noteUserUtterance(1, "удали задачу")
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        val challenge = registry.executeResult(call)
        assertFalse("a challenge is a normal (non-error) tool result", challenge.isError)
        assertEquals("needs_confirmation", outcome(challenge.content))
        assertEquals("an unconfirmed write must never reach the server", 0, client.callToolInvocations)

        // Turn 2: the user says yes; the exact call now executes once.
        store.noteTurnStart(2)
        store.noteUserUtterance(2, "да")
        registry.setAuthorizationContext(2, TurnAuthorization.voice())
        val executed = registry.executeResult(call)
        assertFalse(executed.isError)
        assertEquals("the confirmed write must reach the server exactly once", 1, client.callToolInvocations)

        // Replay within the confirmed turn: single-use, no second execution.
        val replay = registry.executeResult(call)
        assertFalse("a replay is a fresh challenge, not an error", replay.isError)
        assertEquals("needs_confirmation", outcome(replay.content))
        assertEquals("a confirmation must be single-use", 1, client.callToolInvocations)

        // A non-affirmative next turn does NOT confirm the re-armed challenge.
        store.noteTurnStart(3)
        store.noteUserUtterance(3, "нет")
        registry.setAuthorizationContext(3, TurnAuthorization.voice())
        val denied = registry.executeResult(call)
        assertFalse("a bare «нет» yields a fresh challenge, not an error", denied.isError)
        assertEquals("needs_confirmation", outcome(denied.content))
        assertEquals("a non-affirmative turn must not execute", 1, client.callToolInvocations)
    }

    /** Reads the `outcome` discriminator out of a model-facing tool result. */
    private fun outcome(content: String): String =
        Json.parseToJsonElement(content).jsonObject.getValue("outcome").jsonPrimitive.content
}
