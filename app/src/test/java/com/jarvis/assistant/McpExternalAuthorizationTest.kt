package com.jarvis.assistant

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
import kotlinx.coroutines.runBlocking
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

    private fun contract(client: McpClient): McpToolContract = McpToolContract(
        serverId = "srv-1",
        descriptor = McpToolDescriptor("search", "a tool", """{"type":"object","properties":{}}"""),
        client = client,
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
}
