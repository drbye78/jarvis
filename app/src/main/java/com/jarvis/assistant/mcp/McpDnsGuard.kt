package com.jarvis.assistant.mcp

import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Connect-time DNS policy for the MCP lane.
 *
 * [McpUrlPolicy] is deliberately literal-only and NEVER resolves DNS, so a
 * hostname such as `internal.corp` or `10.0.0.5.nip.io` passes it and only
 * betrays itself when it resolves to a private address (DNS rebinding / a
 * private A record). This guard closes that gap at CONNECT time: it wraps the
 * platform resolver and rejects a resolution unless EVERY address it returns is
 * permitted for the server's [kind].
 *
 *  - [McpServerKind.REMOTE] requires every resolved address to be public.
 *  - [McpServerKind.LOCAL] requires every resolved address to be loopback.
 *  - [McpServerKind.LAN] requires every resolved address to be RFC-1918 / ULA
 *    private (the inversion of the REMOTE rule, which is what defeats DNS
 *    rebinding such as `10.0.0.5.nip.io`).
 *
 * A rejection fails closed with a content-free [UnknownHostException] — the
 * shape OkHttp already surfaces for a failed lookup — so neither the hostname
 * nor the resolved address is ever echoed (the MCP lane logs no URLs).
 * Address classification is delegated to [McpUrlPolicy.classifyAddress] over
 * the raw bytes, keeping exactly ONE private-address definition in the lane.
 *
 * One instance is attached to the per-server client built by the composition
 * root; the SHARED graph client is never touched.
 */
class McpDnsGuard private constructor(
    private val kind: McpServerKind,
    private val delegate: Dns,
) : Dns {

    @Throws(UnknownHostException::class)
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        for (address in addresses) {
            val hostClass = McpUrlPolicy.classifyAddress(address)
            val allowed = when (kind) {
                McpServerKind.REMOTE -> hostClass == HostClass.PUBLIC
                McpServerKind.LOCAL -> hostClass == HostClass.LOOPBACK
                McpServerKind.LAN -> hostClass == HostClass.PRIVATE
            }
            if (!allowed) throw UnknownHostException(REJECTED)
        }
        return addresses
    }

    companion object {
        /**
         * The guard for [kind], over the platform resolver by default. [delegate]
         * is injectable so a pure JVM test can script resolutions with no socket.
         */
        fun forServer(kind: McpServerKind, delegate: Dns = Dns.SYSTEM): Dns =
            McpDnsGuard(kind, delegate)

        private const val REJECTED = "resolved address rejected by MCP DNS policy"
    }
}
