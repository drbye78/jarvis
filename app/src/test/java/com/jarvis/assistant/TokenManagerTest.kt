package com.jarvis.assistant

import android.content.SharedPreferences
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.TokenManager
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

/** In-memory SharedPreferences so TokenManager runs on the JVM. */
class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        override fun putString(k: String, v: String?): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putStringSet(k: String, v: MutableSet<String>?): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putInt(k: String, v: Int): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putLong(k: String, v: Long): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putFloat(k: String, v: Float): SharedPreferences.Editor { pending[k] = v; return this }
        override fun putBoolean(k: String, v: Boolean): SharedPreferences.Editor { pending[k] = v; return this }
        override fun remove(k: String): SharedPreferences.Editor { removals.add(k); pending.remove(k); return this }
        override fun clear(): SharedPreferences.Editor { pending.clear(); removals.addAll(map.keys); return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            removals.forEach { map.remove(it) }
            map.putAll(pending)
        }
    }

    override fun getAll(): MutableMap<String, *> = map
    override fun getString(k: String?, def: String?): String? = map[k] as? String ?: def
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(k: String?, def: MutableSet<String>?): MutableSet<String>? =
        map[k] as? MutableSet<String> ?: def
    override fun getInt(k: String?, def: Int): Int = map[k] as? Int ?: def
    override fun getLong(k: String?, def: Long): Long = map[k] as? Long ?: def
    override fun getFloat(k: String?, def: Float): Float = map[k] as? Float ?: def
    override fun getBoolean(k: String?, def: Boolean): Boolean = map[k] as? Boolean ?: def
    override fun contains(k: String?): Boolean = map.containsKey(k)
    override fun edit(): SharedPreferences.Editor = EditorImpl()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

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

    private fun manager(): TokenManager = TokenManager(
        null,
        OkHttpClient(),
        JarvisConfig(oauthEndpoint = server.url("/oauth").toString()),
        FakePrefs(),
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
}
