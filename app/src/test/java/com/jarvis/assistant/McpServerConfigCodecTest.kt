package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpAccess
import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.mcp.McpServerDecodeResult
import com.jarvis.assistant.mcp.McpServerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure codec contract for the persisted MCP server list: canonical round-trip,
 * blank → Empty, malformed → honest Invalid (never a throw), forward-compatible
 * unknown fields, defaults, stable ids and append order.
 */
class McpServerConfigCodecTest {

    private fun config(
        id: String = "srv-1",
        displayName: String = "Alpha",
        kind: McpServerKind = McpServerKind.REMOTE,
        url: String = "https://mcp.example.com",
        enabled: Boolean = true,
        access: McpAccess = McpAccess.READ,
        authHeaderName: String = "Authorization",
        order: Int = 0,
    ) = McpServerConfig(id, displayName, kind, url, enabled, access, authHeaderName, order)

    @Test
    fun `round trip is stable`() {
        val alpha = config()
        val beta = config(
            id = "srv-2",
            displayName = "Beta",
            kind = McpServerKind.LOCAL,
            url = "http://127.0.0.1:8080",
            access = McpAccess.WRITE,
            authHeaderName = "",
            order = 1,
        )
        val original = listOf(alpha, beta)

        val encoded = McpServerConfigCodec.encode(original)
        val decoded = McpServerConfigCodec.decode(encoded)
        assertTrue(decoded is McpServerDecodeResult.Ok)
        val servers = (decoded as McpServerDecodeResult.Ok).servers
        assertEquals(original, servers)
        assertEquals(encoded, McpServerConfigCodec.encode(servers))
    }

    @Test
    fun `blank input decodes to empty`() {
        assertEquals(McpServerDecodeResult.Empty, McpServerConfigCodec.decode(null))
        assertEquals(McpServerDecodeResult.Empty, McpServerConfigCodec.decode(""))
        assertEquals(McpServerDecodeResult.Empty, McpServerConfigCodec.decode("   "))
        assertEquals(McpServerDecodeResult.Empty, McpServerConfigCodec.decode("\n\t"))
    }

    @Test
    fun `empty array decodes to ok empty list`() {
        val decoded = McpServerConfigCodec.decode("[]")
        assertEquals(McpServerDecodeResult.Ok(emptyList()), decoded)
    }

    @Test
    fun `malformed input degrades to invalid without throwing`() {
        val malformed = listOf("{not json", "[{\"id\":}]", "42", "\"a string\"", "{\"servers\":1}")
        for (raw in malformed) {
            val decoded = McpServerConfigCodec.decode(raw)
            assertTrue("expected Invalid for <$raw> but was $decoded", decoded is McpServerDecodeResult.Invalid)
            assertTrue((decoded as McpServerDecodeResult.Invalid).reason.isNotBlank())
        }
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """
            [{"id":"only-id","futureField":123,"nested":{"a":1},"authHeaderName":"X-Api-Key"}]
        """.trimIndent()
        val decoded = McpServerConfigCodec.decode(raw)
        assertTrue(decoded is McpServerDecodeResult.Ok)
        val server = (decoded as McpServerDecodeResult.Ok).servers.single()
        assertEquals("only-id", server.id)
        assertEquals("X-Api-Key", server.authHeaderName)
    }

    @Test
    fun `defaults are applied for omitted fields`() {
        val decoded = McpServerConfigCodec.decode("""[{"id":"only-id"}]""")
        assertTrue(decoded is McpServerDecodeResult.Ok)
        val server = (decoded as McpServerDecodeResult.Ok).servers.single()
        assertEquals("only-id", server.id)
        assertEquals("", server.displayName)
        assertEquals(McpServerKind.REMOTE, server.kind)
        assertEquals("", server.url)
        assertTrue(server.enabled)
        assertEquals(McpAccess.READ, server.access)
        assertEquals("", server.authHeaderName)
        assertEquals(0, server.order)
    }

    @Test
    fun `new servers default to read access`() {
        val created = McpServerConfig.create("Fresh", McpServerKind.REMOTE, "https://mcp.example.com")
        assertEquals(McpAccess.READ, created.access)
        assertTrue(created.id.isNotBlank())
    }

    @Test
    fun `id is stable across round trip and generated per create`() {
        val first = McpServerConfig.create("A", McpServerKind.REMOTE, "https://a.example.com")
        val second = McpServerConfig.create("B", McpServerKind.REMOTE, "https://b.example.com")
        assertNotEquals(first.id, second.id)

        val decoded = McpServerConfigCodec.decode(McpServerConfigCodec.encode(listOf(first)))
        assertEquals(first.id, (decoded as McpServerDecodeResult.Ok).servers.single().id)
    }

    @Test
    fun `create appends order after the highest existing`() {
        val first = McpServerConfig.create("A", McpServerKind.REMOTE, "https://a")
        val second = McpServerConfig.create("B", McpServerKind.REMOTE, "https://b", existing = listOf(first))
        val third = McpServerConfig.create("C", McpServerKind.REMOTE, "https://c", existing = listOf(first, second))
        assertEquals(0, first.order)
        assertEquals(1, second.order)
        assertEquals(2, third.order)

        val gapped = listOf(third, config(id = "x", order = 5))
        assertEquals(6, McpServerConfig.nextOrder(gapped))
    }

    @Test
    fun `encode emits a json array`() {
        val encoded = McpServerConfigCodec.encode(listOf(config()))
        assertTrue(encoded.startsWith("["))
        assertTrue(encoded.endsWith("]"))
    }
}
