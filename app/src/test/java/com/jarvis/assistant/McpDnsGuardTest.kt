package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpDnsGuard
import com.jarvis.assistant.mcp.McpServerKind
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Connect-time DNS policy for the MCP lane (the rebinding gap [McpUrlPolicy]
 * cannot close on its own, because that policy never resolves a name).
 *
 * Pure: the resolver is injected and every address is built with
 * [InetAddress.getByAddress], so no test touches DNS or a socket.
 */
class McpDnsGuardTest {

    /** Returns a scripted resolution without any network I/O. */
    private class FakeDns(private val addresses: List<InetAddress>) : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }

    private fun address(vararg octets: Int): InetAddress =
        InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray())

    private fun remote(vararg addresses: InetAddress): Dns =
        McpDnsGuard.forServer(McpServerKind.REMOTE, FakeDns(addresses.toList()))

    private fun local(vararg addresses: InetAddress): Dns =
        McpDnsGuard.forServer(McpServerKind.LOCAL, FakeDns(addresses.toList()))

    @Test
    fun `remote allows a public address`() {
        assertEquals(1, remote(address(8, 8, 8, 8)).lookup("example.com").size)
    }

    @Test
    fun `remote rejects loopback private link-local metadata and unspecified`() {
        val blocked = listOf(
            address(127, 0, 0, 1), // loopback
            address(10, 0, 0, 5), // RFC-1918
            address(172, 16, 0, 1), // RFC-1918
            address(192, 168, 1, 1), // RFC-1918
            address(169, 254, 1, 1), // link-local
            address(169, 254, 169, 254), // cloud metadata
            address(0, 0, 0, 0), // unspecified
        )
        blocked.forEach { blockedAddress ->
            assertThrows(UnknownHostException::class.java) {
                remote(blockedAddress).lookup("evil.example")
            }
        }
    }

    @Test
    fun `remote rejects when any address in a mixed resolution is private`() {
        assertThrows(UnknownHostException::class.java) {
            remote(address(8, 8, 8, 8), address(10, 0, 0, 1)).lookup("mixed.example")
        }
    }

    @Test
    fun `a hostname resolving to a private address is rejected`() {
        // McpUrlPolicy treats this NAME as public (it never resolves); the guard
        // must catch the private A record it cannot see.
        assertThrows(UnknownHostException::class.java) {
            remote(address(192, 168, 0, 10)).lookup("10.0.0.5.nip.io")
        }
    }

    @Test
    fun `local allows loopback and rejects non-loopback`() {
        assertEquals(1, local(address(127, 0, 0, 1)).lookup("localhost").size)
        assertThrows(UnknownHostException::class.java) {
            local(address(10, 0, 0, 1)).lookup("localhost")
        }
    }
}
