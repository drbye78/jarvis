package com.jarvis.assistant.home.providers.tuya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural pins on the Tuya signing recipe. These assert the pieces the docs
 * fix unambiguously (empty-body hash, uppercasing, query sort order, the
 * clientId/accessToken/t/nonce/stringToSign concatenation) without inventing a
 * known-answer vector — that would only re-assert this implementation.
 */
class TuyaSigningTest {

    @Test
    fun `a bodyless request hashes the empty string`() {
        assertEquals(TuyaSigning.EMPTY_BODY_SHA256, TuyaSigning.contentSha256(null))
        assertEquals(TuyaSigning.EMPTY_BODY_SHA256, TuyaSigning.contentSha256(""))
    }

    @Test
    fun `a body hashes its utf8 bytes`() {
        // SHA-256("{}")
        assertEquals(
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
            TuyaSigning.contentSha256("{}"),
        )
    }

    @Test
    fun `stringToSign is method bodyHash signatureKey url joined by newlines`() {
        val value = TuyaSigning.stringToSign("GET", "/v1.0/token?grant_type=1", null)
        val expected = "GET\n${TuyaSigning.EMPTY_BODY_SHA256}\n\n/v1.0/token?grant_type=1"
        assertEquals(expected, value)
    }

    @Test
    fun `stringToSign uppercases the method`() {
        assertTrue(TuyaSigning.stringToSign("get", "/x", null).startsWith("GET\n"))
    }

    @Test
    fun `query parameters are sorted by key`() {
        val url = TuyaSigning.canonicalUrl("/v1.0/x", listOf("b" to "2", "a" to "1"))
        assertEquals("/v1.0/x?a=1&b=2", url)
    }

    @Test
    fun `canonical url without a query is just the path`() {
        assertEquals("/v1.0/users/u/devices", TuyaSigning.canonicalUrl("/v1.0/users/u/devices"))
    }

    @Test
    fun `the signature is uppercase hex and depends on every component`() {
        val base = TuyaSigning.sign("id", "secret", 100L, "nonce", "token", "STS")
        assertEquals(base, base.uppercase())
        assertTrue(base.matches(Regex("[0-9A-F]{64}")))
        assertNotEquals(base, TuyaSigning.sign("id2", "secret", 100L, "nonce", "token", "STS"))
        assertNotEquals(base, TuyaSigning.sign("id", "secret2", 100L, "nonce", "token", "STS"))
        assertNotEquals(base, TuyaSigning.sign("id", "secret", 101L, "nonce", "token", "STS"))
        assertNotEquals(base, TuyaSigning.sign("id", "secret", 100L, "nonce2", "token", "STS"))
        assertNotEquals(base, TuyaSigning.sign("id", "secret", 100L, "nonce", "token2", "STS"))
        assertNotEquals(base, TuyaSigning.sign("id", "secret", 100L, "nonce", "token", "STS2"))
    }

    @Test
    fun `an empty access token changes the signature - token vs business requests differ`() {
        val tokenReq = TuyaSigning.sign("id", "s", 1L, "n", "", "STS")
        val businessReq = TuyaSigning.sign("id", "s", 1L, "n", "tok", "STS")
        assertNotEquals(tokenReq, businessReq)
    }
}
