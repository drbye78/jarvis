package com.jarvis.assistant.mcp

import java.util.concurrent.ConcurrentHashMap

/**
 * A tiny, pure, thread-safe, process-lifetime first-time gate for warning logs.
 *
 * Keys are STABLE per misconfiguration (for example
 * `url:<serverId>:<UrlRejection>`), so a supplier that re-runs on every
 * [McpToolCatalog.snapshot] logs a given warning exactly ONCE for the life of
 * the process. A misconfiguration that is fixed simply stops producing its key;
 * no explicit reset is needed, and a DIFFERENT reason on the same server logs
 * afresh because the key differs.
 *
 * Backed by a [ConcurrentHashMap] key set — `add` returns true exactly once per
 * distinct key, which is the required first-time semantics.
 */
class McpWarningThrottle {

    private val seen = ConcurrentHashMap.newKeySet<String>()

    /** True the first time [key] is presented, false on every later call. */
    fun firstTime(key: String): Boolean = seen.add(key)
}
