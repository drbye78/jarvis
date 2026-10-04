package com.jarvis.assistant.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure rules of the request-hardening layer (§14.3): the `Host` allow-list, the
 * Go `CrossOriginProtection` decision and the response security headers.
 */
class ManagementSecurityTest {

    private val allowed = setOf("localhost", "127.0.0.1", "[::1]", "192.168.1.50")

    @Test
    fun `host allow list accepts configured hosts with or without port`() {
        assertTrue(ManagementSecurity.isAllowedHost("localhost", allowed))
        assertTrue(ManagementSecurity.isAllowedHost("localhost:8765", allowed))
        assertTrue(ManagementSecurity.isAllowedHost("127.0.0.1:8765", allowed))
        assertTrue(ManagementSecurity.isAllowedHost("[::1]:8765", allowed))
        assertTrue(ManagementSecurity.isAllowedHost("::1", allowed))
        assertTrue(ManagementSecurity.isAllowedHost("192.168.1.50:8765", allowed))
        assertTrue("host comparison is case-insensitive", ManagementSecurity.isAllowedHost("LOCALHOST", allowed))
    }

    @Test
    fun `host allow list rejects rebinding hosts and absent host`() {
        assertFalse(ManagementSecurity.isAllowedHost(null, allowed))
        assertFalse(ManagementSecurity.isAllowedHost("", allowed))
        assertFalse(ManagementSecurity.isAllowedHost("evil.test", allowed))
        assertFalse(ManagementSecurity.isAllowedHost("localhost.evil.test", allowed))
        assertFalse(ManagementSecurity.isAllowedHost("127.0.0.1.evil.test", allowed))
        assertFalse(ManagementSecurity.isAllowedHost("192.168.1.51", allowed))
    }

    @Test
    fun `safe methods are always allowed even cross origin`() {
        assertTrue(ManagementSecurity.isCrossOriginAllowed("GET", null, "https://evil.test", "127.0.0.1:8765"))
        assertTrue(ManagementSecurity.isCrossOriginAllowed("HEAD", "cross-site", "https://evil.test", "127.0.0.1:8765"))
        assertTrue(ManagementSecurity.isCrossOriginAllowed("OPTIONS", null, null, null))
    }

    @Test
    fun `unsafe requests with same-origin or none sec-fetch-site are allowed`() {
        assertTrue(
            ManagementSecurity.isCrossOriginAllowed("PUT", "same-origin", null, "127.0.0.1:8765"),
        )
        assertTrue(
            "Sec-Fetch-Site: none is a user navigation, NOT a rejection",
            ManagementSecurity.isCrossOriginAllowed("POST", "none", null, "127.0.0.1:8765"),
        )
        assertTrue(
            ManagementSecurity.isCrossOriginAllowed("DELETE", "same-origin", "https://evil.test", "127.0.0.1:8765"),
        )
    }

    @Test
    fun `unsafe requests require an origin that equals the host`() {
        assertTrue(
            ManagementSecurity.isCrossOriginAllowed(
                "PUT",
                null,
                "https://127.0.0.1:8765",
                "127.0.0.1:8765",
            ),
        )
        assertTrue(
            "same-site is not trusted by itself; a matching Origin still allows",
            ManagementSecurity.isCrossOriginAllowed("PUT", "same-site", "https://127.0.0.1:8765", "127.0.0.1:8765"),
        )
        assertFalse(
            ManagementSecurity.isCrossOriginAllowed("PUT", null, "https://evil.test", "127.0.0.1:8765"),
        )
        assertFalse(
            "an Origin port mismatch is cross-origin",
            ManagementSecurity.isCrossOriginAllowed("PUT", null, "https://127.0.0.1:9999", "127.0.0.1:8765"),
        )
    }

    @Test
    fun `unsafe requests without origin or host are rejected`() {
        assertFalse(ManagementSecurity.isCrossOriginAllowed("POST", null, null, "127.0.0.1:8765"))
        assertFalse(ManagementSecurity.isCrossOriginAllowed("POST", "cross-site", null, "127.0.0.1:8765"))
        assertFalse(ManagementSecurity.isCrossOriginAllowed("POST", null, "https://127.0.0.1:8765", null))
        assertFalse(
            "a malformed origin is not trusted",
            ManagementSecurity.isCrossOriginAllowed("POST", null, "not a url", "127.0.0.1:8765"),
        )
    }

    @Test
    fun `security headers are fixed and the csp is html only`() {
        val base = ManagementSecurity.securityHeaders(html = false)
        assertEquals("nosniff", base[ManagementSecurity.HEADER_CONTENT_TYPE_OPTIONS])
        assertEquals("DENY", base[ManagementSecurity.HEADER_FRAME_OPTIONS])
        assertEquals("no-referrer", base[ManagementSecurity.HEADER_REFERRER_POLICY])
        assertFalse(base.containsKey(ManagementSecurity.HEADER_CONTENT_SECURITY_POLICY))

        val html = ManagementSecurity.securityHeaders(html = true)
        assertEquals(
            ManagementSecurity.CONTENT_SECURITY_POLICY,
            html[ManagementSecurity.HEADER_CONTENT_SECURITY_POLICY],
        )
        assertFalse("no CORS headers are ever emitted", html.keys.any { it.startsWith("Access-Control-") })
    }
}
