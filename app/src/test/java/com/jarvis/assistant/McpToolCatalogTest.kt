package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpAccess
import com.jarvis.assistant.mcp.McpBudget
import com.jarvis.assistant.mcp.McpCall2
import com.jarvis.assistant.mcp.McpClient
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpToolCatalog
import com.jarvis.assistant.mcp.McpToolContract
import com.jarvis.assistant.mcp.McpToolDescriptor
import com.jarvis.assistant.mcp.McpToolResult2
import com.jarvis.assistant.mcp.McpToolsCall
import com.jarvis.assistant.mcp.McpToolsPage
import com.jarvis.assistant.tools.ToolRisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Catalog behaviour: enable/access filtering, per-server + global caps,
 * deterministic ordering, failure isolation and stale-while-revalidate.
 *
 * Discovery runs in a sibling scope on the test scheduler (the established
 * repo pattern): `runTest`'s own job must not wait for it, and background
 * scope's tasks are not reliably advanced.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class McpToolCatalogTest {

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    /** A cancelable discovery scope sharing the test scheduler. */
    private fun TestScope.discoveryScope(): CoroutineScope {
        val created = CoroutineScope(coroutineContext + SupervisorJob())
        scope = created
        return created
    }

    private class FakeClient(
        var init: McpCall2 = McpCall2.Ok(McpToolResult2("", false)),
        var list: McpToolsCall = McpToolsCall.Ok(McpToolsPage(emptyList(), null)),
    ) : McpClient {
        var initCalls = 0
        var listCalls = 0
        var closeCalls = 0

        override suspend fun initialize(): McpCall2 {
            initCalls++
            return init
        }

        override suspend fun listTools(): McpToolsCall {
            listCalls++
            return list
        }

        override suspend fun callTool(name: String, argumentsJson: String): McpCall2 =
            McpCall2.Ok(McpToolResult2("ok", false))

        override suspend fun close() {
            closeCalls++
        }
    }

    private fun server(
        id: String,
        order: Int,
        access: McpAccess = McpAccess.READ,
        enabled: Boolean = true,
    ) = McpServerConfig(id = id, displayName = id, order = order, access = access, enabled = enabled)

    private fun tools(count: Int, prefix: String = "t"): List<McpToolDescriptor> =
        (0 until count).map { McpToolDescriptor("$prefix$it", "d", """{"type":"object","properties":{}}""") }

    private fun ok(list: List<McpToolDescriptor>): McpToolsCall =
        McpToolsCall.Ok(McpToolsPage(list, nextCursor = null))

    @Test
    fun `snapshot is non-blocking and reads nothing before discovery`() = runTest {
        val client = FakeClient(list = ok(tools(1)))
        val catalog = McpToolCatalog({ listOf(server("a", 0)) }, { client }, discoveryScope())

        assertTrue("no cache yet means no tools", catalog.snapshot().isEmpty())
        assertEquals("snapshot must not perform I/O", 0, client.initCalls)
        assertEquals("snapshot must not perform I/O", 0, client.listCalls)

        advanceUntilIdle()
        assertEquals("the background refresh ran exactly once", 1, client.listCalls)
        assertEquals(1, catalog.snapshot().size)
    }

    @Test
    fun `a disabled server contributes nothing and is never discovered`() = runTest {
        val client = FakeClient(list = ok(tools(2)))
        val catalog = McpToolCatalog({ listOf(server("a", 0, enabled = false)) }, { client }, discoveryScope())

        assertTrue(catalog.snapshot().isEmpty())
        catalog.refreshAll()
        advanceUntilIdle()
        assertTrue(catalog.snapshot().isEmpty())
        assertEquals(0, client.initCalls)
    }

    @Test
    fun `a write server advertises tools tagged external write`() = runTest {
        val client = FakeClient(list = ok(tools(3)))
        val catalog = McpToolCatalog(
            { listOf(server("a", 0, access = McpAccess.WRITE)) },
            { client },
            discoveryScope(),
        )

        catalog.refreshAll()

        val contracts = catalog.snapshot().filterIsInstance<McpToolContract>()
        assertEquals(3, contracts.size)
        assertTrue(
            "every write server tool must be tagged EXTERNAL_WRITE",
            contracts.all { it.risk == ToolRisk.EXTERNAL_WRITE },
        )
    }

    @Test
    fun `a read server advertises tools tagged external`() = runTest {
        val client = FakeClient(list = ok(tools(2)))
        val catalog = McpToolCatalog(
            { listOf(server("a", 0, access = McpAccess.READ)) },
            { client },
            discoveryScope(),
        )
        catalog.refreshAll()

        val contracts = catalog.snapshot().filterIsInstance<McpToolContract>()
        assertEquals(2, contracts.size)
        assertTrue(
            "every read server tool must be tagged EXTERNAL",
            contracts.all { it.risk == ToolRisk.EXTERNAL },
        )
    }

    @Test
    fun `the per-server cap truncates deterministically`() = runTest {
        val client = FakeClient(list = ok(tools(McpBudget.MAX_TOOLS_PER_SERVER + 5)))
        val catalog = McpToolCatalog({ listOf(server("a", 0)) }, { client }, discoveryScope())
        catalog.refreshAll()

        val first = catalog.snapshot().map { it.name }
        val second = catalog.snapshot().map { it.name }
        assertEquals(McpBudget.MAX_TOOLS_PER_SERVER, first.size)
        assertEquals(first, second)
    }

    @Test
    fun `the global cap bounds the union deterministically`() = runTest {
        val ids = listOf("a", "b", "c")
        val clients = ids.associateWith { FakeClient(list = ok(tools(McpBudget.MAX_TOOLS_PER_SERVER))) }
        val catalog = McpToolCatalog(
            { ids.mapIndexed { index, id -> server(id, order = index) } },
            { clients.getValue(it.id) },
            discoveryScope(),
        )
        catalog.refreshAll()

        val first = catalog.snapshot().map { it.name }
        val second = catalog.snapshot().map { it.name }
        assertEquals(McpBudget.MAX_ADVERTISED_TOOLS, first.size)
        assertEquals(first, second)
    }

    @Test
    fun `one server's discovery failure is isolated`() = runTest {
        val bad = FakeClient(init = McpCall2.Unreachable)
        val good = FakeClient(list = ok(listOf(McpToolDescriptor("ok", "d", EMPTY))))
        val clients = mapOf("bad" to bad, "good" to good)
        val catalog = McpToolCatalog(
            { listOf(server("bad", 0), server("good", 1)) },
            { clients.getValue(it.id) },
            discoveryScope(),
        )
        catalog.refreshAll()

        val contracts = catalog.snapshot().filterIsInstance<McpToolContract>()
        assertEquals(listOf("ok"), contracts.map { it.originalToolName })
        assertEquals("a failing server must not be probed further", 0, bad.listCalls)
        assertEquals(1, good.listCalls)
    }

    @Test
    fun `stale cache is served while at most one background refresh runs`() = runTest {
        var now = 0L
        val client = FakeClient(list = ok(tools(1)))
        val catalog = McpToolCatalog(
            { listOf(server("a", 0)) },
            { client },
            discoveryScope(),
            ttlMillis = 1_000,
            clock = { now },
        )
        catalog.refreshAll()
        assertEquals(1, client.listCalls)

        now = 5_000
        assertEquals("the stale list must still be served", 1, catalog.snapshot().size)
        assertEquals("a second stale snapshot must not schedule a second refresh", 1, catalog.snapshot().size)
        assertEquals(1, client.listCalls)

        advanceUntilIdle()
        assertEquals("exactly one revalidation ran", 2, client.listCalls)
    }

    @Test
    fun `advertisedNames projects the snapshot's namespaced names`() = runTest {
        val client = FakeClient(list = ok(tools(2, prefix = "x")))
        val catalog = McpToolCatalog({ listOf(server("a", 0)) }, { client }, discoveryScope())
        catalog.refreshAll()

        val expected = catalog.snapshot().map { it.name }.toSet()
        assertEquals(expected, catalog.advertisedNames())
        assertEquals(2, catalog.advertisedNames().size)
    }

    private companion object {
        const val EMPTY = """{"type":"object","properties":{}}"""
    }
}
