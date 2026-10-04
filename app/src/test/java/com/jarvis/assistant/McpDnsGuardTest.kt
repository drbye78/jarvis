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

    /** An IPv4-mapped IPv6 address (`::ffff:a.b.c.d`) from its four IPv4 octets. */
    private fun mapped(vararg octets: Int): InetAddress {
        val bytes = ByteArray(16)
        bytes[10] = 0xFF.toByte()
        bytes[11] = 0xFF.toByte()
        for (i in 0 until 4) bytes[12 + i] = octets[i].toByte()
        return InetAddress.getByAddress(bytes)
    }

    /** An IPv6 unique-local address in `fc00::/7`. */
    private fun ula(): InetAddress {
        val bytes = ByteArray(16)
        bytes[0] = 0xFC.toByte()
        bytes[15] = 0x01
        return InetAddress.getByAddress(bytes)
    }

    private fun remote(vararg addresses: InetAddress): Dns =
        McpDnsGuard.forServer(McpServerKind.REMOTE, FakeDns(addresses.toList()))

    private fun local(vararg addresses: InetAddress): Dns =
        McpDnsGuard.forServer(McpServerKind.LOCAL, FakeDns(addresses.toList()))

    private fun lan(vararg addresses: InetAddress): Dns =
        McpDnsGuard.forServer(McpServerKind.LAN, FakeDns(addresses.toList()))

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

    @Test
    fun `lan allows RFC-1918 and ULA addresses`() {
        assertEquals(1, lan(address(10, 0, 0, 5)).lookup("hub.local").size)
        assertEquals(1, lan(address(172, 16, 0, 1)).lookup("hub.local").size)
        assertEquals(1, lan(address(192, 168, 1, 50)).lookup("hub.local").size)
        assertEquals(1, lan(ula()).lookup("hub.local").size)
    }

    @Test
    fun `lan rejects public loopback link-local metadata and unspecified`() {
        val blocked = listOf(
            address(8, 8, 8, 8), // public
            address(127, 0, 0, 1), // loopback
            address(169, 254, 1, 1), // link-local
            address(169, 254, 169, 254), // cloud metadata
            address(0, 0, 0, 0), // unspecified
        )
        blocked.forEach { blockedAddress ->
            assertThrows(UnknownHostException::class.java) {
                lan(blockedAddress).lookup("rebind.example")
            }
        }
    }

    @Test
    fun `lan rejects a mixed private and public resolution`() {
        assertThrows(UnknownHostException::class.java) {
            lan(address(192, 168, 1, 50), address(8, 8, 8, 8)).lookup("mixed.example")
        }
    }

    @Test
    fun `address classification uses raw bytes for a mapped IPv6 private address`() {
        // `::ffff:10.0.0.5` is RFC-1918 once the v4-mapped bytes are unwrapped;
        // classifying from the hostAddress STRING would misread it.
        assertEquals(1, lan(mapped(10, 0, 0, 5)).lookup("hub.local").size)
        assertThrows(UnknownHostException::class.java) {
            remote(mapped(10, 0, 0, 5)).lookup("rebind.example")
        }
    }
}
