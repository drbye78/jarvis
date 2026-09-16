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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

class TokenManagerTest {

    private lateinit var server: MockWebServer

    /**
     * Captures what the log lane would persist. `FileLoggingTree` writes the
     * formatted message AND `Log.getStackTraceString(t)` of an attached
     * throwable, so [rendered] rebuilds both halves — a leak in EITHER is a
     * leak on disk.
     */
    private class CapturingTree : Timber.Tree() {
        data class Line(val priority: Int, val message: String, val throwable: Throwable?)

        val lines = mutableListOf<Line>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            synchronized(lines) { lines.add(Line(priority, message, t)) }
        }

        /** Everything a reader of the log file would actually see. */
        fun rendered(): String = buildString {
            for (line in lines) {
                append(line.priority).append(' ').append(line.message).append('\n')
                var cause: Throwable? = line.throwable
                while (cause != null) {
                    append(cause.javaClass.name).append(": ").append(cause.message).append('\n')
                    cause.stackTrace.forEach { append("    at ").append(it).append('\n') }
                    cause = cause.cause
                }
            }
        }
    }

    private val tree = CapturingTree()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
        server.shutdown()
    }

    private fun manager(vault: SecretVault = InMemoryVault()): TokenManager = TokenManager(
        null,
        OkHttpClient(),
        JarvisConfig(oauthEndpoint = server.url("/oauth").toString()),
        vault,
    ) { _ -> "test-client" to "test-secret" }

    /**
     * The leak contract of audit decision #11, stated as a CHAIN property.
     *
     * On device (assertions off) the sanitized failure is rethrown with NO
     * cause at all. In unit tests the JVM runs with -ea, which switches
     * kotlinx-coroutines into debug mode: `withContext` then rethrows a
     * stack-trace-recovery COPY whose cause is the original sanitized failure
     * (`_COROUTINE._BOUNDARY` frames) — so `cause == null` is unsatisfiable
     * in tests even though the device behavior is exact. The copy is harmless
     * (same sanitized message); what must NEVER be reachable anywhere in the
     * chain is the raw serialization exception, whose message quotes the
     * response body. Every legitimate link is the sanitizer's own
     * RuntimeException, so pinning the class of every link pins the property.
     */
    private fun assertSanitizedChain(error: Throwable) {
        var link: Throwable? = error
        while (link != null) {
            assertTrue(
                "chain link must be the sanitizer's own RuntimeException, " +
                    "got ${link.javaClass.name}: ${link.message}",
                link.javaClass == RuntimeException::class.java,
            )
            link = link.cause
        }
    }

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

    /**
     * Audit decision #11. `kotlinx.serialization` exception messages quote the
     * input they tripped on — measured on the project's 1.8.1: a truncated body
     * comes back as `JsonDecodingException: … JSON input:
     * {"access_token":"Bearer sk-…` — so keeping the CAUSE while sanitizing the
     * message leaks anyway (callers log turns with `Timber.e(e, …)` and
     * FileLoggingTree persists it). The rethrow must therefore carry NO cause.
     */
    @Test
    fun `truncated oauth body is rethrown without a cause at all`() = runBlocking {
        val leaky = """{"access_token":"Bearer sk-SUPER-SECRET-MATERIAL", "expires_in":"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(leaky))

        val error = runCatching { manager().getGigaChatToken() }.exceptionOrNull()

        assertTrue("expected a RuntimeException", error is RuntimeException)
        assertSanitizedChain(error!!)
        val message = error.message!!
        assertTrue(message.contains("not valid JSON"))
        assertTrue(message.contains("HTTP 200"))
        assertTrue("cause TYPE must still travel inside the message: $message",
            message.contains("JsonDecodingException"))
        assertFalse(message.contains("SUPER-SECRET"))
        assertFalse(message.contains("access_token"))
        assertFalse(message.contains("JSON input"))
    }

    @Test
    fun `object-typed access_token is rejected without echoing the response`() = runBlocking {
        // Well-formed JSON, wrong shape: `jsonPrimitive` raises here, and the
        // same sanitizer has to cover it.
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":{"inner":"LEAKED-TOKEN-OBJECT"},"expires_in":3600}""")
        )

        val error = runCatching { manager().getGigaChatToken() }.exceptionOrNull()

        assertSanitizedChain(error!!)
        val message = error.message!!
        assertTrue("unexpected message: $message", message.contains("unexpected 'access_token' type"))
        assertTrue(message.contains("IllegalArgumentException"))
        assertFalse(message.contains("LEAKED-TOKEN-OBJECT"))
    }

    @Test
    fun `nothing reaching the log lane carries oauth response bytes`() = runBlocking {
        Timber.uprootAll()
        Timber.plant(tree)
        val leaky = """{"access_token":"Bearer sk-SUPER-SECRET-MATERIAL", "expires_in":"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(leaky))
        runCatching { manager().getGigaChatToken() }.exceptionOrNull()

        val errors = tree.lines.filter { it.priority == 6 }
        assertTrue("the rejection must be logged at ERROR", errors.isNotEmpty())
        assertTrue(
            "the log line keeps the diagnosis (status + length + cause class) without the body",
            errors.any {
                it.message.contains("HTTP 200") &&
                    it.message.contains("unparseable body") &&
                    it.message.contains("JsonDecodingException")
            },
        )
        assertTrue(
            "no throwable may ride along: its message embeds the response",
            tree.lines.all { it.throwable == null },
        )
        val rendered = tree.rendered()
        assertFalse("leaked into the persisted log:\n$rendered", rendered.contains("SUPER-SECRET"))
        assertFalse(rendered.contains("expires_in"))
    }

    @Test
    fun `an unparseable expiry shape degrades to the fallback window and stays clean`() = runBlocking {
        Timber.uprootAll()
        Timber.plant(tree)
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"tok-shape","expires_in":{"leak":"LEAK-EXPIRY-7"}}""")
        )
        val vault = InMemoryVault()
        val tm = manager(vault)

        // A bad expiry hint must not kill a usable token (audit #14 behavior),
        // and must not carry the offending element into message or log.
        assertEquals("tok-shape", tm.getGigaChatToken())
        val expiry = vault.getString(SecretVault.KEY_GIGACHAT_EXPIRY)!!.toLong()
        val now = System.currentTimeMillis()
        assertTrue(
            "expected the conservative fallback window, got +${expiry - now} ms",
            expiry in (now + 4 * 60 * 1000L)..(now + 6 * 60 * 1000L),
        )
        val rendered = tree.rendered()
        assertFalse("leaked into the persisted log:\n$rendered", rendered.contains("LEAK-EXPIRY-7"))
        assertTrue(
            "the degradation must still be visible in the log",
            tree.lines.any { it.priority == 5 && it.message.contains("fallback window") },
        )
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
