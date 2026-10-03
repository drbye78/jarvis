package com.jarvis.assistant

import com.jarvis.assistant.mcp.HostClass
import com.jarvis.assistant.mcp.McpServerKind
import com.jarvis.assistant.mcp.McpUrlPolicy
import com.jarvis.assistant.mcp.UrlPolicyResult
import com.jarvis.assistant.mcp.UrlRejection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Truth table for the pure SSRF/localhost boundary.
 *
 * REMOTE must be https to a public host; every loopback / RFC-1918 / link-local
 * / metadata literal is rejected. LOCAL must be loopback and is the only place
 * cleartext http is allowed. Hostnames other than `localhost` are treated as
 * public because this check performs no DNS (documented in [McpUrlPolicy]).
 */
class McpUrlPolicyTest {

    private fun allowed(kind: McpServerKind, url: String) =
        assertEquals("expected ALLOWED for $url", UrlPolicyResult.Allowed, McpUrlPolicy.validate(kind, url))

    private fun rejected(kind: McpServerKind, url: String, reason: UrlRejection) =
        assertEquals("expected $reason for $url", UrlPolicyResult.Rejected(reason), McpUrlPolicy.validate(kind, url))

    @Test
    fun `remote https public hosts are allowed`() {
        allowed(McpServerKind.REMOTE, "https://mcp.example.com")
        allowed(McpServerKind.REMOTE, "https://api.example.com/mcp?x=1")
        allowed(McpServerKind.REMOTE, "https://8.8.8.8/mcp")
        allowed(McpServerKind.REMOTE, "https://172.32.0.1/mcp")
    }

    @Test
    fun `remote http is rejected`() {
        rejected(McpServerKind.REMOTE, "http://mcp.example.com", UrlRejection.HTTPS_REQUIRED)
        rejected(McpServerKind.REMOTE, "http://8.8.8.8/mcp", UrlRejection.HTTPS_REQUIRED)
    }

    @Test
    fun `remote non http schemes are rejected`() {
        rejected(McpServerKind.REMOTE, "ftp://mcp.example.com", UrlRejection.UNSUPPORTED_SCHEME)
        rejected(McpServerKind.REMOTE, "file:///etc/passwd", UrlRejection.UNSUPPORTED_SCHEME)
        rejected(McpServerKind.REMOTE, "mcp.example.com", UrlRejection.UNSUPPORTED_SCHEME)
    }

    @Test
    fun `remote loopback private link local and metadata literals are rejected`() {
        rejected(McpServerKind.REMOTE, "https://127.0.0.1/mcp", UrlRejection.LOOPBACK_HOST)
        rejected(McpServerKind.REMOTE, "https://localhost/mcp", UrlRejection.LOOPBACK_HOST)
        rejected(McpServerKind.REMOTE, "https://[::1]/mcp", UrlRejection.LOOPBACK_HOST)
        rejected(McpServerKind.REMOTE, "https://10.0.0.5/mcp", UrlRejection.PRIVATE_HOST)
        rejected(McpServerKind.REMOTE, "https://172.16.0.1/mcp", UrlRejection.PRIVATE_HOST)
        rejected(McpServerKind.REMOTE, "https://172.31.255.255/mcp", UrlRejection.PRIVATE_HOST)
        rejected(McpServerKind.REMOTE, "https://192.168.1.1/mcp", UrlRejection.PRIVATE_HOST)
        rejected(McpServerKind.REMOTE, "https://0.0.0.0/mcp", UrlRejection.PRIVATE_HOST)
        rejected(McpServerKind.REMOTE, "https://[fd00::1]/mcp", UrlRejection.PRIVATE_HOST)
        rejected(McpServerKind.REMOTE, "https://169.254.1.1/mcp", UrlRejection.LINK_LOCAL_HOST)
        rejected(McpServerKind.REMOTE, "https://[fe80::1]/mcp", UrlRejection.LINK_LOCAL_HOST)
        rejected(McpServerKind.REMOTE, "https://169.254.169.254/latest/meta-data/", UrlRejection.METADATA_HOST)
    }

    @Test
    fun `remote malformed and missing host are rejected`() {
        rejected(McpServerKind.REMOTE, "https:///mcp", UrlRejection.MISSING_HOST)
        rejected(McpServerKind.REMOTE, "https://exa mple.com", UrlRejection.MALFORMED)
    }

    @Test
    fun `local loopback is allowed over http and https`() {
        allowed(McpServerKind.LOCAL, "http://127.0.0.1:8080")
        allowed(McpServerKind.LOCAL, "https://127.0.0.1:8443/mcp")
        allowed(McpServerKind.LOCAL, "http://127.0.0.5:9000")
        allowed(McpServerKind.LOCAL, "http://localhost:3000")
        allowed(McpServerKind.LOCAL, "http://[::1]:8080")
    }

    @Test
    fun `local non loopback is rejected`() {
        rejected(McpServerKind.LOCAL, "http://192.168.1.1:8080", UrlRejection.NON_LOOPBACK_HOST)
        rejected(McpServerKind.LOCAL, "http://mcp.example.com", UrlRejection.NON_LOOPBACK_HOST)
        rejected(McpServerKind.LOCAL, "https://[fd00::1]", UrlRejection.NON_LOOPBACK_HOST)
    }

    @Test
    fun `local scheme rules and malformed input`() {
        rejected(McpServerKind.LOCAL, "ftp://127.0.0.1", UrlRejection.UNSUPPORTED_SCHEME)
        rejected(McpServerKind.LOCAL, "127.0.0.1", UrlRejection.UNSUPPORTED_SCHEME)
        rejected(McpServerKind.LOCAL, "http://exa mple.com", UrlRejection.MALFORMED)
    }

    @Test
    fun `redirect within or across public hosts is allowed for remote`() {
        val sameHost = McpUrlPolicy.validateRedirect(
            McpServerKind.REMOTE,
            "https://mcp.example.com/a",
            "https://mcp.example.com/b",
        )
        assertEquals(UrlPolicyResult.Allowed, sameHost)
        val otherPublic = McpUrlPolicy.validateRedirect(
            McpServerKind.REMOTE,
            "https://mcp.example.com/a",
            "https://api.other-public.test/b",
        )
        assertEquals(UrlPolicyResult.Allowed, otherPublic)
    }

    @Test
    fun `remote redirect to a private host is rejected`() {
        val result = McpUrlPolicy.validateRedirect(
            McpServerKind.REMOTE,
            "https://mcp.example.com/a",
            "https://169.254.169.254/latest/meta-data/",
        )
        assertEquals(UrlPolicyResult.Rejected(UrlRejection.CROSS_ORIGIN_PRIVATE_HOST), result)
    }

    @Test
    fun `remote redirect scheme downgrade is rejected`() {
        val result = McpUrlPolicy.validateRedirect(
            McpServerKind.REMOTE,
            "https://mcp.example.com/a",
            "http://mcp.example.com/b",
        )
        assertEquals(UrlPolicyResult.Rejected(UrlRejection.HTTPS_REQUIRED), result)
    }

    @Test
    fun `local redirect stays loopback only`() {
        val ok = McpUrlPolicy.validateRedirect(
            McpServerKind.LOCAL,
            "http://127.0.0.1/a",
            "http://127.0.0.1/b",
        )
        assertEquals(UrlPolicyResult.Allowed, ok)
        val crossed = McpUrlPolicy.validateRedirect(
            McpServerKind.LOCAL,
            "http://127.0.0.1/a",
            "http://10.0.0.1/b",
        )
        assertEquals(UrlPolicyResult.Rejected(UrlRejection.NON_LOOPBACK_HOST), crossed)
    }

    @Test
    fun `host classification handles literals and localhost`() {
        assertEquals(HostClass.LOOPBACK, McpUrlPolicy.classifyHost("localhost"))
        assertEquals(HostClass.LOOPBACK, McpUrlPolicy.classifyHost("::1"))
        assertEquals(HostClass.LOOPBACK, McpUrlPolicy.classifyHost("[::1]"))
        assertEquals(HostClass.METADATA, McpUrlPolicy.classifyHost("169.254.169.254"))
        assertEquals(HostClass.PRIVATE, McpUrlPolicy.classifyHost("fd00::1"))
        assertEquals(HostClass.PUBLIC, McpUrlPolicy.classifyHost("mcp.example.com"))
        assertEquals(HostClass.MALFORMED, McpUrlPolicy.classifyHost("999.1.1.1"))
    }
}
