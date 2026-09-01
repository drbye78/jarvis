package com.jarvis.assistant

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.CredentialCheck
import com.jarvis.assistant.llm.OAuthCredentialValidator
import com.jarvis.assistant.llm.OAuthScopes
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioral tests for the credential validation probe: status mapping,
 * network failure classification, request-shape parity with the real token
 * fetch, per-scope distinction, and the blank guard.
 */
class OAuthCredentialValidatorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun validator() = OAuthCredentialValidator(
        config = JarvisConfig(oauthEndpoint = server.url("/oauth").toString()),
        httpClient = OkHttpClient(),
    )

    @Test
    fun `2xx with access_token is Valid`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"tok","expires_in":3600}""")
        )
        assertEquals(CredentialCheck.Valid, validator().checkGigaChat("id", "sec"))
    }

    @Test
    fun `401 and 403 are Invalid - user fixable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"auth_error"}"""))
        assertEquals(CredentialCheck.Invalid(401), validator().checkSalute("id", "sec"))

        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(CredentialCheck.Invalid(403), validator().checkSalute("id", "sec"))
    }

    @Test
    fun `5xx and 429 are Unverifiable - not a verdict on the credentials`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(validator().checkGigaChat("id", "sec") is CredentialCheck.Unverifiable)

        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(validator().checkGigaChat("id", "sec") is CredentialCheck.Unverifiable)
    }

    @Test
    fun `200 without a parsable token is Unverifiable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))
        assertTrue(validator().checkGigaChat("id", "sec") is CredentialCheck.Unverifiable)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"no_token_field":true}"""))
        assertTrue(validator().checkGigaChat("id", "sec") is CredentialCheck.Unverifiable)
    }

    @Test
    fun `network failure is Unverifiable`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(validator().checkSalute("id", "sec") is CredentialCheck.Unverifiable)
    }

    @Test
    fun `probe request mirrors the token fetch shape`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"t"}"""))

        validator().checkSalute("cid", "csec")

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals(Credentials.basic("cid", "csec"), recorded.getHeader("Authorization"))
        assertNotNull(recorded.getHeader("RqUID"))
        val body = recorded.body.readUtf8()
        assertTrue("body must carry the Salute scope: $body", body.contains("scope=${OAuthScopes.SALUTE}"))
        assertTrue("body must carry the grant type: $body", body.contains("grant_type=client_credentials"))
    }

    @Test
    fun `salute and gigachat probes carry different scopes`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"t"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"access_token":"t"}"""))

        validator().checkSalute("a", "b")
        validator().checkGigaChat("a", "b")

        assertTrue(server.takeRequest().body.readUtf8().contains("scope=${OAuthScopes.SALUTE}"))
        assertTrue(server.takeRequest().body.readUtf8().contains("scope=${OAuthScopes.GIGACHAT}"))
    }

    @Test
    fun `blank pair is Unverifiable and never hits the network`() = runBlocking {
        val v = validator()
        assertTrue(v.checkGigaChat("", "x") is CredentialCheck.Unverifiable)
        assertTrue(v.checkSalute("x", "") is CredentialCheck.Unverifiable)
        assertEquals(0, server.requestCount)
    }
}
