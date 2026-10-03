package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpServerConfig
import com.jarvis.assistant.mcp.McpServerConfigCodec
import com.jarvis.assistant.mcp.McpServerKind
import com.jarvis.assistant.mcp.McpServerValidation
import com.jarvis.assistant.mcp.McpServerValidationResult
import com.jarvis.assistant.mcp.Rejected
import com.jarvis.assistant.mcp.UrlRejection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure contract for the policy-applied MCP server validation: blank → Empty,
 * malformed → honest Invalid (never a throw), and a decodable list partitioned
 * by the URL policy with input order preserved and the name fallback applied.
 */
class McpServerValidationTest {

    private fun server(id: String, name: String, url: String) = McpServerConfig(
        id = id,
        displayName = name,
        kind = McpServerKind.REMOTE,
        url = url,
    )

    @Test
    fun `blank input is empty`() {
        assertEquals(McpServerValidationResult.Empty, McpServerValidation.validate(""))
        assertEquals(McpServerValidationResult.Empty, McpServerValidation.validate("   "))
        assertEquals(McpServerValidationResult.Empty, McpServerValidation.validate("\n\t"))
    }

    @Test
    fun `malformed input is invalid without throwing`() {
        val malformed = listOf("{not json", "[{\"id\":}]", "42", "\"a string\"")
        for (raw in malformed) {
            val result = McpServerValidation.validate(raw)
            assertTrue("expected Invalid for <$raw> but was $result", result is McpServerValidationResult.Invalid)
            assertTrue((result as McpServerValidationResult.Invalid).reason.isNotBlank())
        }
    }

    @Test
    fun `an empty array decodes to an empty decoded list`() {
        val result = McpServerValidation.validate("[]")
        assertEquals(McpServerValidationResult.Decoded(emptyList(), emptyList()), result)
    }

    @Test
    fun `decodable list is partitioned preserving order with the name fallback`() {
        val raw = McpServerConfigCodec.encode(
            listOf(
                server(id = "a", name = "Alpha", url = "https://a.example.com"),
                // Blank displayName must fall back to the id in the rejection.
                server(id = "b", name = "", url = "https://127.0.0.1/mcp"),
                server(id = "c", name = "Gamma", url = "https://c.example.com"),
                server(id = "d", name = "Delta", url = "https://10.0.0.5/mcp"),
            ),
        )

        val result = McpServerValidation.validate(raw)
        assertTrue(result is McpServerValidationResult.Decoded)
        val decoded = result as McpServerValidationResult.Decoded

        assertEquals("valid servers keep input order", listOf("a", "c"), decoded.valid.map { it.id })
        assertEquals(
            "rejections keep input order, with the right reason and name",
            listOf(
                Rejected(serverId = "b", name = "b", reason = UrlRejection.LOOPBACK_HOST),
                Rejected(serverId = "d", name = "Delta", reason = UrlRejection.PRIVATE_HOST),
            ),
            decoded.rejected,
        )
    }

    @Test
    fun `a list where every server is rejected yields no valid servers`() {
        val raw = McpServerConfigCodec.encode(
            listOf(server(id = "a", name = "Alpha", url = "https://169.254.169.254/latest/meta-data/")),
        )
        val decoded = McpServerValidation.validate(raw) as McpServerValidationResult.Decoded
        assertTrue(decoded.valid.isEmpty())
        assertEquals(
            listOf(Rejected("a", "Alpha", UrlRejection.METADATA_HOST)),
            decoded.rejected,
        )
    }
}
