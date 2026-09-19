package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * SberAuthorizationKey: accept Sber's combined `base64("<id>:<secret>")`
 * authorization key pasted into either credential field, while never
 * reinterpreting an ordinary secret that merely looks like Base64.
 *
 * All values here are synthetic — never a real credential.
 */
class SberAuthorizationKeyTest {

    private val clientId = "12345678-1234-1234-1234-123456789abc"
    private val secret = "abcdef0123456789"

    private fun combined(id: String = clientId, secretValue: String = secret): String =
        Base64.getEncoder().encodeToString("$id:$secretValue".toByteArray(Charsets.UTF_8))

    @Test
    fun `real shape splits into the exact halves`() {
        assertEquals(clientId to secret, SberAuthorizationKey.split(combined()))
    }

    @Test
    fun `splits with the trailing padding removed`() {
        val padded = combined()
        assertTrue("vector must carry padding", padded.endsWith('='))
        assertEquals(clientId to secret, SberAuthorizationKey.split(padded.trimEnd('=')))
    }

    @Test
    fun `splits the URL-safe alphabet variant`() {
        val urlSafe = Base64.getUrlEncoder()
            .encodeToString("$clientId:??".toByteArray(Charsets.UTF_8))
        assertTrue("vector must use the URL-safe alphabet", urlSafe.any { it == '_' || it == '-' })
        assertEquals(clientId to "??", SberAuthorizationKey.split(urlSafe))
    }

    @Test
    fun `a 36-char hex secret is not misread as a combined key`() {
        assertNull(SberAuthorizationKey.split("0123456789abcdef0123456789abcdef0123"))
    }

    @Test
    fun `base64 without a colon is rejected`() {
        val noColon = Base64.getEncoder()
            .encodeToString("no colon in this payload".toByteArray(Charsets.UTF_8))
        assertNull(SberAuthorizationKey.split(noColon))
    }

    @Test
    fun `base64 with two colons is rejected`() {
        val twoColons = Base64.getEncoder()
            .encodeToString("$clientId:$secret:extra".toByteArray(Charsets.UTF_8))
        assertNull(SberAuthorizationKey.split(twoColons))
    }

    @Test
    fun `a left half of the wrong length is rejected`() {
        val shortId = Base64.getEncoder()
            .encodeToString("short-id:$secret".toByteArray(Charsets.UTF_8))
        assertNull(SberAuthorizationKey.split(shortId))
    }

    @Test
    fun `blank and whitespace inputs are rejected`() {
        assertNull(SberAuthorizationKey.split(""))
        assertNull(SberAuthorizationKey.split("   "))
    }

    @Test
    fun `surrounding whitespace is trimmed before splitting`() {
        val padded = "  ${combined()}\n"
        assertEquals(clientId to secret, SberAuthorizationKey.split(padded))
    }

    @Test
    fun `normalize prefers a combined key in the secret field`() {
        assertEquals(clientId to secret, SberAuthorizationKey.normalize("", combined()))
    }

    @Test
    fun `normalize secret wins over a populated id`() {
        assertEquals(clientId to secret, SberAuthorizationKey.normalize("some-other-id", combined()))
    }

    @Test
    fun `normalize also splits a combined key in the id field`() {
        assertEquals(clientId to secret, SberAuthorizationKey.normalize(combined(), secret))
    }

    @Test
    fun `normalize passes an ordinary pair through trimmed`() {
        assertEquals("id" to "secret", SberAuthorizationKey.normalize("  id  ", " secret "))
    }

    @Test
    fun `normalize of blank fields stays blank`() {
        assertEquals("" to "", SberAuthorizationKey.normalize(" ", "  "))
    }
}
