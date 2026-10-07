package com.jarvis.assistant

import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.tools.EMPTY_PARAMETER_SCHEMA
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.ToolResult
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.TurnAuthorization
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The OPT-IN failed-retry dedupe: a tool whose repeat of the EXACT same call is
 * provably pointless (the music cascade) can suppress an identical failed retry
 * within a turn. The guard is deliberately opt-in — a global one would suppress
 * legitimate transient retries (a warming-up GPS fix) — so the "did not opt in"
 * case is pinned here too.
 */
class ToolRegistryRetryGuardTest {

    /** A READ_ONLY probe whose outcome the test controls. */
    private class RetryProbeTool(
        private val fail: () -> Boolean,
        override val deduplicateFailedRetries: Boolean,
    ) : ToolContract {
        var invocations = 0
        override val name = "retryProbe"
        override val risk = ToolRisk.READ_ONLY
        override val description = "probe"
        override val parametersJson = EMPTY_PARAMETER_SCHEMA

        override suspend fun execute(arguments: String): String = """{"ok":true}"""

        override suspend fun executeResult(arguments: String): ToolResult {
            invocations++
            return if (fail()) {
                ToolResult("""{"outcome":"failed","detail":"nope"}""", isError = true)
            } else {
                ToolResult("""{"outcome":"success"}""")
            }
        }
    }

    @Test
    fun `an identical failed call short-circuits without re-executing`() = runBlocking {
        val tool = RetryProbeTool(fail = { true }, deduplicateFailedRetries = true)
        val registry = ToolRegistry(listOf(tool))

        val first = registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))
        val second = registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))

        assertTrue(first.isError)
        assertTrue(second.isError)
        // Executed once; the second call returned the cached body verbatim.
        assertEquals(1, tool.invocations)
        assertEquals(first.content, second.content)
    }

    @Test
    fun `a differing-arguments call still executes`() = runBlocking {
        val tool = RetryProbeTool(fail = { true }, deduplicateFailedRetries = true)
        val registry = ToolRegistry(listOf(tool))

        registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))
        registry.executeResult(FunctionCall(tool.name, """{"q":"y"}"""))

        assertEquals(2, tool.invocations)
    }

    @Test
    fun `a success is never cached`() = runBlocking {
        val tool = RetryProbeTool(fail = { false }, deduplicateFailedRetries = true)
        val registry = ToolRegistry(listOf(tool))

        val first = registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))
        val second = registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals(2, tool.invocations)
    }

    @Test
    fun `a new turn bind clears the failed-retry cache`() = runBlocking {
        val tool = RetryProbeTool(fail = { true }, deduplicateFailedRetries = true)
        val registry = ToolRegistry(listOf(tool))

        registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))
        // TurnRunner binds a non-null context at every turn start; that is the
        // per-turn reset.
        registry.setAuthorizationContext(1, TurnAuthorization.voice())
        registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))

        assertEquals(2, tool.invocations)
    }

    @Test
    fun `a tool that did not opt in is not deduplicated`() = runBlocking {
        val tool = RetryProbeTool(fail = { true }, deduplicateFailedRetries = false)
        val registry = ToolRegistry(listOf(tool))

        registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))
        registry.executeResult(FunctionCall(tool.name, """{"q":"x"}"""))

        assertEquals(2, tool.invocations)
    }
}
