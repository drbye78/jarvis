package com.jarvis.assistant.mcp

import com.jarvis.assistant.tools.ToolContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the configured MCP servers and exposes the union of their discovered
 * tools to the [com.jarvis.assistant.tools.ToolRegistry] dynamic seam.
 *
 * Android-free by construction: the config supplier, the client factory and
 * the discovery scope are all injected, so this class never constructs an
 * OkHttp client or reads a pref directly.
 *
 * ## Write policy
 *
 * ENABLED servers contribute tools regardless of access. A WRITE server's
 * discovered tools are advertised too, but tagged [com.jarvis.assistant.tools.ToolRisk.EXTERNAL_WRITE]
 * and gated by the registry's confirmation clause: the model may request one,
 * but it only executes after the user explicitly confirms the exact call on the
 * immediately-next voice turn. Advertising is safe because an unconfirmed
 * write never reaches the server.
 *
 * ## Stale-while-revalidate
 *
 * [snapshot] never blocks and never performs I/O. When a server's cached tool
 * list is missing or older than [ttlMillis], snapshot keeps serving whatever
 * it already has (possibly nothing) and schedules AT MOST ONE background
 * [refresh] for that server. The refresh swaps the cached list atomically on
 * completion, so a per-turn re-projection sees discoveries without rebuilding
 * the registry.
 *
 * Auth header VALUES are not this class's concern — the injected factory owns
 * them. Logs carry only the server NAME and a coarse failure REASON, never a
 * URL, a secret or server payload content.
 */
class McpToolCatalog(
    /** Re-read per snapshot, so enable/disable/access changes are LIVE. */
    private val configSupplier: () -> List<McpServerConfig>,
    /** Builds a client for one server; owns auth and transport concerns. */
    private val clientFactory: (McpServerConfig) -> McpClient,
    /** Runs background discovery; never blocks [snapshot]. */
    private val scope: CoroutineScope,
    /** How long a cached tool list is served before a background refresh. */
    private val ttlMillis: Long = DEFAULT_TTL_MS,
    /** Injectable clock so TTL expiry is testable. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class CachedServer(val contracts: List<McpToolContract>, val fetchedAtMillis: Long)

    private val cache = ConcurrentHashMap<String, CachedServer>()

    /** Guards against piling up concurrent refreshes for one server. */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /**
     * The tools to advertise right now. Non-blocking, I/O-free, and safe to
     * call on every LLM pass. Order is deterministic: servers by `(order, id)`,
     * tools in discovery order, then the global cap.
     */
    fun snapshot(): List<ToolContract> {
        val now = clock()
        val servers = configSupplier()
            .filter { it.enabled }
            .sortedWith(compareBy({ it.order }, { it.id }))
        val advertised = ArrayList<ToolContract>()
        for (server in servers) {
            val cached = cache[server.id]
            if (cached == null || now - cached.fetchedAtMillis >= ttlMillis) {
                triggerRefresh(server.id)
            }
            val (kept, _) = McpBudget.capTools(cached?.contracts.orEmpty())
            for (tool in kept) {
                if (advertised.size >= McpBudget.MAX_ADVERTISED_TOOLS) break
                if (advertised.none { it.name == tool.name }) advertised += tool
            }
            if (advertised.size >= McpBudget.MAX_ADVERTISED_TOOLS) break
        }
        return advertised
    }

    /**
     * The namespaced names advertised right now — the dynamic slip-tool set
     * ([com.jarvis.assistant.data.ConversationManager] must not treat a
     * discovered tool name as a verbatim chat slip). Read-only projection of
     * [snapshot]; performs no work of its own.
     */
    fun advertisedNames(): Set<String> = snapshot().map { it.name }.toSet()

    /**
     * Discover one server's tools now. A no-op when the server is already
     * being discovered. Isolated: any failure is logged and leaves the prior
     * cache intact; [CancellationException] always propagates.
     */
    suspend fun refresh(serverId: String) {
        if (!inFlight.add(serverId)) return
        try {
            doRefresh(serverId)
        } finally {
            inFlight.remove(serverId)
        }
    }

    /** Discover every ENABLED server, one at a time; one failure never stops the rest. */
    suspend fun refreshAll() {
        configSupplier()
            .filter { it.enabled }
            .sortedWith(compareBy({ it.order }, { it.id }))
            .forEach { refresh(it.id) }
    }

    private fun triggerRefresh(serverId: String) {
        if (!inFlight.add(serverId)) return
        scope.launch {
            try {
                doRefresh(serverId)
            } finally {
                inFlight.remove(serverId)
            }
        }
    }

    private suspend fun doRefresh(serverId: String) {
        val server = configSupplier().firstOrNull { it.id == serverId } ?: return
        val client = clientFactory(server)
        var discovered: List<McpToolContract>? = null
        try {
            when (val init = client.initialize()) {
                is McpCall2.Ok -> when (val listed = client.listTools()) {
                    is McpToolsCall.Ok -> discovered = toContracts(server, listed.page.tools, client)
                    else -> logFailure(server, reasonOf(listed))
                }
                else -> logFailure(server, reasonOf(init))
            }
        } catch (e: CancellationException) {
            closeQuietly(client)
            throw e
        } catch (e: Exception) {
            Timber.w(e, "MCP server %s discovery failed: %s", serverName(server), "exception")
        }
        if (discovered != null) {
            cache[server.id] = CachedServer(discovered, clock())
            // A server that advertised nothing has no contract to hold the
            // client, so release it instead of leaking the session.
            if (discovered.isEmpty()) closeQuietly(client)
        } else {
            // Keep serving the stale list but restamp so an unreachable server
            // is retried after the TTL, not on every snapshot.
            cache[server.id] = CachedServer(cache[server.id]?.contracts.orEmpty(), clock())
            closeQuietly(client)
        }
    }

    private fun toContracts(
        server: McpServerConfig,
        descriptors: List<McpToolDescriptor>,
        client: McpClient,
    ): List<McpToolContract> {
        val (kept, dropped) = McpBudget.capTools(descriptors)
        if (dropped > 0) {
            Timber.d(
                "MCP server %s advertised %d tools; %d dropped by cap",
                serverName(server),
                descriptors.size,
                dropped,
            )
        }
        return kept.map { McpToolContract(server.id, it, client, server.access) }
    }

    private fun logFailure(server: McpServerConfig, reason: String) {
        Timber.w("MCP server %s discovery failed: %s", serverName(server), reason)
    }

    private fun closeQuietly(client: McpClient) {
        // Non-blocking: a slow close must not stall discovery or the caller.
        scope.launch {
            try {
                client.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "MCP client close failed (ignored)")
            }
        }
    }

    private fun reasonOf(call: McpCall2): String = when (call) {
        is McpCall2.Ok -> "ok"
        is McpCall2.ProtocolError -> "protocol error ${call.code}"
        McpCall2.Unreachable -> "unreachable"
        McpCall2.BadResponse -> "bad response"
    }

    private fun reasonOf(call: McpToolsCall): String = when (call) {
        is McpToolsCall.Ok -> "ok"
        is McpToolsCall.ProtocolError -> "protocol error ${call.code}"
        McpToolsCall.Unreachable -> "unreachable"
        McpToolsCall.BadResponse -> "bad response"
    }

    private fun serverName(server: McpServerConfig): String =
        server.displayName.takeIf { it.isNotBlank() } ?: server.id

    companion object {
        /** Default revalidation window: discovery is not a per-turn concern. */
        const val DEFAULT_TTL_MS: Long = 5 * 60 * 1000
    }
}
