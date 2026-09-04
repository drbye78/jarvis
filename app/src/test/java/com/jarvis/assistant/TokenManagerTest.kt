package com.jarvis.assistant

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class TokenManagerTest {

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

    private fun manager(vault: SecretVault = InMemoryVault()): TokenManager = TokenManager(
        null,
        OkHttpClient(),
        JarvisConfig(oauthEndpoint = server.url("/oauth").toString()),
        vault,
    ) { _ -> "test-client" to "test-secret" }

    @Test
    fun `malformed oauth 200 body never leaks into exception message`() = runBlocking {
        // Not JSON at all — a malformed 200 that echoes token-adjacent material.
        val secretBody = "ACCESS_TOKEN=sk-SUPER-SECRET-MATERIAL&token_hint=leak-me"
        server.enqueue(MockResponse().setResponseCode(200).setBody(secretBody))

        val error = runCatching { manager().getGigaChatToken() }.exceptionOrNull()

        assertTrue("expected a RuntimeException", error is RuntimeException)
        val message = error!!.message!!
        assertTrue(message.contains("not valid JSON"))
        assertTrue(message.contains("HTTP 200"))
        assertTrue(message.contains("scope='GIGACHAT_API_PERS'"))
        // The raw body (and anything adjacent to secrets in it) must be absent.
        assertFalse(message.contains("SUPER-SECRET"))
        assertFalse(message.contains("token_hint"))
        assertFalse(message.contains("ACCESS_TOKEN"))

        // Valid JSON but missing access_token: same sanitization rule.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"error":"no_token_field","hint":"second-leak"}""")
        )
        val error2 = runCatching { manager().getGigaChatToken() }.exceptionOrNull()
        val message2 = error2!!.message!!
        assertTrue(message2.contains("missing 'access_token'"))
        assertFalse(message2.contains("no_token_field"))
        assertFalse(message2.contains("second-leak"))
    }

    @Test
    fun `http error status surfaces sanitized without body text`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("upstream said: leak-me"))
        val error = runCatching { manager().getGigaChatToken() }.exceptionOrNull()
        val message = error!!.message!!
        assertTrue(message.contains("HTTP 401"))
        assertFalse(message.contains("leak-me"))
    }

    @Test
    fun `cancelling during token fetch aborts the http request`() = runBlocking {
        // Headers delayed well beyond the test budget: only cancellation can
        // end this await promptly.
        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"t","expires_in":3600}""")
                .setHeadersDelay(4, TimeUnit.SECONDS)
        )
        val tm = manager()

        val job = launch(Dispatchers.IO) { runCatching { tm.getGigaChatToken() } }
        withTimeout(5_000) {
            while (server.requestCount == 0) delay(10) // request reached the server
        }

        val elapsed = measureTimeMillis {
            job.cancel()
            job.join()
        }

        assertTrue("await did not abort on cancellation (${elapsed}ms)", elapsed < 3_000)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `successful refresh caches token and second call skips the network`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"tok-1","expires_in":3600}""")
        )
        val tm = manager()

        assertEquals("tok-1", tm.getGigaChatToken())
        assertEquals("tok-1", tm.getGigaChatToken()) // served from cache
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `response without any expiry hint caches for the short fallback only (audit 14)`() = runBlocking {
        // Neither expires_at nor expires_in: the old code cached for a blind
        // hour; the conservative fallback must be ~5 minutes so an odd
        // provider response degrades into an early refresh, not a stale token.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"t-no-expiry"}""")
        )
        val vault = InMemoryVault()
        val tm = TokenManager(
            null,
            OkHttpClient(),
            JarvisConfig(oauthEndpoint = server.url("/oauth").toString()),
            vault,
        ) { _ -> "test-client" to "test-secret" }

        assertEquals("t-no-expiry", tm.getGigaChatToken())

        val expiry = vault.getString(SecretVault.KEY_GIGACHAT_EXPIRY)!!.toLong()
        val now = System.currentTimeMillis()
        assertTrue(
            "fallback expiry must stay under 6 min from now, was +${expiry - now} ms",
            expiry in (now + 4 * 60 * 1000L)..(now + 6 * 60 * 1000L),
        )
    }

    @Test
    fun `invalidate clears cached tokens so the next call re-authenticates`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"tok-1","expires_in":3600}""")
        )
        val vault = InMemoryVault()
        val tm = manager(vault)
        assertEquals("tok-1", tm.getGigaChatToken())

        tm.invalidate()

        assertTrue(
            "invalidate must drop the cached gigachat token",
            vault.getString(SecretVault.KEY_GIGACHAT_TOKEN).isNullOrBlank(),
        )
        assertTrue(
            "invalidate must drop the cached gigachat expiry",
            vault.getString(SecretVault.KEY_GIGACHAT_EXPIRY).isNullOrBlank(),
        )
    }
}
