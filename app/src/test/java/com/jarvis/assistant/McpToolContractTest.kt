package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpAccess
import com.jarvis.assistant.mcp.McpBudget
import com.jarvis.assistant.mcp.McpCall2
import com.jarvis.assistant.mcp.McpClient
import com.jarvis.assistant.mcp.McpToolContract
import com.jarvis.assistant.mcp.McpToolDescriptor
import com.jarvis.assistant.mcp.McpToolResult2
import com.jarvis.assistant.mcp.McpToolsCall
import com.jarvis.assistant.mcp.McpToolsPage
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.tools.EMPTY_PARAMETER_SCHEMA
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.ToolRisks
import com.jarvis.assistant.tools.TurnAuthorization
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MCP tool adapter: namespacing/collision rule, schema fallback, result
 * capping, error mapping and the registry end-to-end classification.
 */
class McpToolContractTest {

    /** Configurable [McpClient]; records the last call. */
    private class FakeClient(
        var callResult: McpCall2 = McpCall2.Ok(McpToolResult2("ok", false)),
    ) : McpClient {
        var callCount = 0
        var lastCallName: String? = null
        var lastArguments: String? = null

        override suspend fun initialize(): McpCall2 = McpCall2.Ok(McpToolResult2("", false))
        override suspend fun listTools(): McpToolsCall = McpToolsCall.Ok(McpToolsPage(emptyList(), null))

        override suspend fun callTool(name: String, argumentsJson: String): McpCall2 {
            callCount++
            lastCallName = name
            lastArguments = argumentsJson
            return callResult
        }

        override suspend fun close() {}
    }

    private fun descriptor(
        name: String = "search",
        schema: String = """{"type":"object","properties":{}}""",
        description: String = "d",
    ) = McpToolDescriptor(name, description, schema)

    private fun contract(
        client: McpClient = FakeClient(),
        serverId: String = "server-1",
        descriptor: McpToolDescriptor = descriptor(),
        access: McpAccess = McpAccess.READ,
    ) = McpToolContract(serverId, descriptor, client, access)

    // ------------------------------------------------------------------
    // Namespacing / collision rule
    // ------------------------------------------------------------------

    @Test
    fun `namespaced name never equals a built-in tool name`() {
        ToolRisks.byName.keys.forEach { builtIn ->
            val namespaced = McpToolContract.namespacedName("server-1", builtIn)
            assertNotEquals(builtIn, namespaced)
            assertTrue(namespaced.startsWith(McpToolContract.NAMESPACE_PREFIX))
        }
    }

    @Test
    fun `namespaced name is deterministic and capped at 64 chars`() {
        val longTool = "x".repeat(400)
        val first = McpToolContract.namespacedName("server-1", longTool)
        val second = McpToolContract.namespacedName("server-1", longTool)
        assertEquals(first, second)
        assertTrue("length ${first.length}", first.length <= McpToolContract.MAX_NAME_LENGTH)
    }

    @Test
    fun `truncation keeps a deterministic hash and stays unique`() {
        val prefix = "x".repeat(200)
        val a = McpToolContract.namespacedName("server-1", prefix + "A")
        val b = McpToolContract.namespacedName("server-1", prefix + "B")
        assertNotEquals("hash suffix must disambiguate", a, b)
        assertEquals(a, McpToolContract.namespacedName("server-1", prefix + "A"))
    }

    @Test
    fun `the same tool name on different servers resolves to different names`() {
        val one = McpToolContract.namespacedName("server-a", "search")
        val two = McpToolContract.namespacedName("server-b", "search")
        assertNotEquals(one, two)
    }

    @Test
    fun `unsafe characters are sanitized out of the LLM name`() {
        val name = McpToolContract.namespacedName("server-1", "we/ird tool!☃")
        assertTrue("'$name' must be LLM-safe", name.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun `reverse mapping exposes the server id and original tool name`() {
        val subject = contract(serverId = "server-7", descriptor = descriptor(name = "read_file"))
        assertEquals("server-7", subject.serverId)
        assertEquals("read_file", subject.originalToolName)
        assertEquals(McpToolContract.namespacedName("server-7", "read_file"), subject.name)
    }

    @Test
    fun `risk follows the server access class`() {
        assertEquals(ToolRisk.EXTERNAL, contract(access = McpAccess.READ).risk)
        assertEquals(ToolRisk.EXTERNAL_WRITE, contract(access = McpAccess.WRITE).risk)
    }

    // ------------------------------------------------------------------
    // Description / schema budgets
    // ------------------------------------------------------------------

    @Test
    fun `description is capped`() {
        val long = "a".repeat(McpBudget.MAX_DESCRIPTION_CHARS + 50)
        val subject = contract(descriptor = descriptor(description = long))
        assertEquals(McpBudget.MAX_DESCRIPTION_CHARS, subject.description.length)
        assertTrue(subject.description.endsWith(McpBudget.DESCRIPTION_TRUNCATION_MARKER))
    }

    @Test
    fun `missing description degrades to empty, not null`() {
        val subject = contract(descriptor = descriptor(description = ""))
        assertEquals("", subject.description)
    }

    @Test
    fun `a valid schema is preserved raw`() {
        val raw = """{"type":"object","properties":{"q":{"type":"string"}}}"""
        assertEquals(raw, contract(descriptor = descriptor(schema = raw)).parametersJson)
    }

    @Test
    fun `invalid, non-object and oversized schemas fall back to the empty schema`() {
        val notJson = contract(descriptor = descriptor(schema = "{not json"))
        val notObject = contract(descriptor = descriptor(schema = """["array"]"""))
        val oversized = contract(
            descriptor = descriptor(schema = "{\"x\":\"" + "a".repeat(McpBudget.MAX_SCHEMA_BYTES) + "\"}"),
        )
        val values = listOf(notJson, notObject, oversized).map { it.parametersJson }
        values.forEach { assertEquals(EMPTY_PARAMETER_SCHEMA, it) }
    }

    // ------------------------------------------------------------------
    // Execution mapping
    // ------------------------------------------------------------------

    @Test
    fun `ok result is capped and the server error flag is preserved`() = runTest {
        val huge = FakeClient(McpCall2.Ok(McpToolResult2("a".repeat(McpBudget.MAX_RESULT_BYTES + 100), false)))
        val capped = contract(client = huge).executeResult("{}")
        assertTrue(capped.content.endsWith(McpBudget.RESULT_TRUNCATION_MARKER))
        assertFalse(capped.isError)

        val toolError = FakeClient(McpCall2.Ok(McpToolResult2("boom", isError = true)))
        assertTrue(contract(client = toolError).executeResult("{}").isError)
    }

    @Test
    fun `protocol, unreachable and bad-response map to honest errors`() = runTest {
        val outcomes = listOf(
            McpCall2.ProtocolError(code = -32601, message = "method not found"),
            McpCall2.Unreachable,
            McpCall2.BadResponse,
        )
        outcomes.forEach { outcome ->
            val result = contract(client = FakeClient(outcome)).executeResult("{}")
            assertTrue("$outcome must be an error", result.isError)
            assertTrue(result.content.contains("error"))
        }
    }

    @Test
    fun `the original tool name and arguments travel to the client`() = runTest {
        val client = FakeClient()
        val subject = contract(client = client, descriptor = descriptor(name = "read_file"))
        subject.executeResult("""{"path":"/x"}""")
        assertEquals("read_file", client.lastCallName)
        assertEquals("""{"path":"/x"}""", client.lastArguments)
    }

    @Test
    fun `cancellation from the client propagates, never flattened`() {
        val cancelling = object : McpClient {
            override suspend fun initialize(): McpCall2 = McpCall2.Unreachable
            override suspend fun listTools(): McpToolsCall = McpToolsCall.Unreachable
            override suspend fun callTool(name: String, argumentsJson: String): McpCall2 =
                throw CancellationException("barge-in")
            override suspend fun close() {}
        }
        val subject = contract(client = cancelling)
        assertThrows(CancellationException::class.java) {
            runBlocking { subject.executeResult("{}") }
        }
    }

    @Test
    fun `registry denies an external tool without a voice turn and allows it with one`() = runBlocking {
        val subject = contract()
        val registry = ToolRegistry(emptyList(), dynamicTools = { listOf(subject) })

        assertTrue(
            "no bound context must deny an external tool",
            registry.executeResult(FunctionCall(subject.name, "{}")).isError,
        )
        registry.setAuthorizationContext(1, TurnAuthorization.system())
        assertTrue(
            "a scheduled/proactive-free system turn must deny it",
            registry.executeResult(FunctionCall(subject.name, "{}")).isError,
        )
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        assertFalse(
            "a user voice turn must allow it",
            registry.executeResult(FunctionCall(subject.name, "{}")).isError,
        )
    }

    @Test
    fun `registry surfaces an MCP protocol error as isError`() = runBlocking {
        val subject = contract(client = FakeClient(McpCall2.ProtocolError(-32000, "nope")))
        val registry = ToolRegistry(emptyList(), dynamicTools = { listOf(subject) })
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        val result = registry.executeResult(FunctionCall(subject.name, "{}"))
        assertTrue("the structured error must reach the registry boundary", result.isError)
    }
}
