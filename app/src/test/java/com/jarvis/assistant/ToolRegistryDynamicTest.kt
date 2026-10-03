package com.jarvis.assistant

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.TurnAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dynamic-registry seam: a per-call projection of discovered tools that
 * does NOT participate in the static cross-check or the authorization-context
 * binding.
 */
class ToolRegistryDynamicTest {

    private class ProbeTool(
        override val name: String,
        override val risk: ToolRisk,
    ) : ToolContract {
        var invocations = 0
        override val description = "probe"
        override val parametersJson = """{"type":"object","properties":{}}"""
        override suspend fun execute(arguments: String): String {
            invocations++
            return """{"ok":true}"""
        }
    }

    @Test
    fun `dynamic tools are reflected on every call without a rebuild`() {
        var dynamic: List<ToolContract> = emptyList()
        val registry = ToolRegistry(
            listOf(ProbeTool("setVolume", ToolRisk.STATEFUL)),
            dynamicTools = { dynamic },
        )
        assertFalse(registry.available().any { it.name == "mcp_a_thing" })

        dynamic = listOf(ProbeTool("mcp_a_thing", ToolRisk.EXTERNAL))
        assertTrue(registry.available().any { it.name == "mcp_a_thing" })
        assertTrue(registry.getToolDefinitions().any { it.name == "mcp_a_thing" })

        dynamic = emptyList()
        assertFalse(registry.available().any { it.name == "mcp_a_thing" })
        assertFalse(registry.getToolDefinitions().any { it.name == "mcp_a_thing" })
    }

    @Test
    fun `dynamic tools are exempt from the static risk cross-check`() {
        // If the dynamic supplier were cross-checked, constructing this
        // registry would throw (setVolume is STATEFUL in ToolRisks). The
        // static pin must stay on the static list only.
        val registry = ToolRegistry(
            listOf(ProbeTool("setVolume", ToolRisk.STATEFUL)),
            dynamicTools = { listOf(ProbeTool("setVolume", ToolRisk.READ_ONLY)) },
        )
        assertTrue(registry.available().any { it.name == "setVolume" })
    }

    @Test
    fun `a dynamic external tool is denied off the voice lane and allowed on it`() = runBlocking {
        val tool = ProbeTool("mcp_a_thing", ToolRisk.EXTERNAL)
        val registry = ToolRegistry(emptyList(), dynamicTools = { listOf(tool) })

        assertTrue(
            "no context must deny",
            registry.executeResult(FunctionCall(tool.name, "{}")).isError,
        )
        registry.setAuthorizationContext(1, TurnAuthorization.system())
        assertTrue(
            "a system turn must deny",
            registry.executeResult(FunctionCall(tool.name, "{}")).isError,
        )
        assertEquals("a denied call must not run", 0, tool.invocations)

        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        assertFalse(
            "a voice turn must allow",
            registry.executeResult(FunctionCall(tool.name, "{}")).isError,
        )
        assertEquals(1, tool.invocations)
    }

    @Test
    fun `static authorization semantics are unchanged by the dynamic supplier`() = runBlocking {
        val stateful = ProbeTool("setVolume", ToolRisk.STATEFUL)
        val registry = ToolRegistry(
            listOf(stateful),
            dynamicTools = { listOf(ProbeTool("mcp_a_thing", ToolRisk.EXTERNAL)) },
        )
        assertTrue(
            "STATEFUL still fails closed with no bound context",
            registry.executeResult(FunctionCall("setVolume", "{}")).isError,
        )
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        assertFalse(registry.executeResult(FunctionCall("setVolume", "{}")).isError)
        assertEquals(1, stateful.invocations)
    }

    @Test
    fun `a stale dynamic name is an honest unknown-function error`() = runBlocking {
        var dynamic: List<ToolContract> = listOf(ProbeTool("mcp_a_thing", ToolRisk.EXTERNAL))
        val registry = ToolRegistry(emptyList(), dynamicTools = { dynamic })
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        assertFalse(registry.executeResult(FunctionCall("mcp_a_thing", "{}")).isError)

        dynamic = emptyList()
        val stale = registry.executeResult(FunctionCall("mcp_a_thing", "{}"))
        assertTrue(stale.isError)
        assertTrue(stale.content.contains("Unknown function"))
    }
}
