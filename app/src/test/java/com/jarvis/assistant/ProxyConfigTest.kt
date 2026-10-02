package com.jarvis.assistant

import com.jarvis.assistant.weather.LiveProxySelector
import com.jarvis.assistant.weather.ProxyScheme
import com.jarvis.assistant.weather.javaProxy
import com.jarvis.assistant.weather.parse
import com.jarvis.assistant.weather.proxyAuthenticator
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * Pure-JVM tests for the Open-Meteo proxy parser/selector/authenticator.
 *
 * The selector and authenticator are the fail-open boundary: a blank or broken
 * pref must yield a DIRECT connection (never break weather), and the
 * authenticator must answer proxy challenges only — an origin-server 401 must
 * stay untouched because this authenticator is attached to a weather-only client.
 */
class ProxyConfigTest {

    @Test
    fun `bare host and port parse`() {
        val spec = parse("proxy.local:8080")
        assertNotNull(spec)
        assertEquals("proxy.local", spec!!.host)
        assertEquals(8080, spec.port)
        assertEquals(ProxyScheme.HTTP, spec.scheme)
        assertNull(spec.username)
        assertNull(spec.password)
    }

    @Test
    fun `http and https schemes both map to an http proxy`() {
        assertEquals(ProxyScheme.HTTP, parse("http://proxy.local:8080")!!.scheme)
        assertEquals(ProxyScheme.HTTP, parse("https://proxy.local:8080")!!.scheme)
    }

    @Test
    fun `socks5 maps to a socks proxy`() {
        val spec = parse("socks5://proxy.local:1080")
        assertNotNull(spec)
        assertEquals(ProxyScheme.SOCKS, spec!!.scheme)
        assertEquals(1080, spec.port)
    }

    @Test
    fun `userinfo is split into username and password`() {
        val spec = parse("http://alice:s3cret@proxy.local:3128")!!
        assertEquals("proxy.local", spec.host)
        assertEquals(3128, spec.port)
        assertEquals("alice", spec.username)
        assertEquals("s3cret", spec.password)
    }

    @Test
    fun `username without a password is accepted`() {
        val spec = parse("alice@proxy.local:3128")!!
        assertEquals("alice", spec.username)
        assertEquals("", spec.password)
    }

    @Test
    fun `blank and malformed inputs return null`() {
        assertNull(parse(""))
        assertNull(parse("   "))
        assertNull(parse("proxy.local"))
        assertNull(parse(":8080"))
        assertNull(parse("proxy.local:"))
        assertNull(parse("ftp://proxy.local:8080"))
        assertNull(parse("proxy.local:notaport"))
    }

    @Test
    fun `out-of-range ports return null`() {
        assertNull(parse("proxy.local:0"))
        assertNull(parse("proxy.local:70000"))
        assertNull(parse("proxy.local:-1"))
    }

    @Test
    fun `javaProxy picks the type from the scheme`() {
        val http = javaProxy(parse("http://proxy.local:8080")!!)
        assertEquals(Proxy.Type.HTTP, http.type())
        val address = http.address() as InetSocketAddress
        assertEquals("proxy.local", address.hostString)
        assertEquals(8080, address.port)

        val socks = javaProxy(parse("socks5://proxy.local:1080")!!)
        assertEquals(Proxy.Type.SOCKS, socks.type())
    }

    @Test
    fun `selector fails open to direct for a blank pref`() {
        val selector = LiveProxySelector { "" }
        val selected = selector.select(URI("https://api.open-meteo.com/v1/forecast"))
        assertEquals(1, selected.size)
        assertSame(Proxy.NO_PROXY, selected.first())
    }

    @Test
    fun `selector returns the configured proxy`() {
        val selector = LiveProxySelector { "http://proxy.local:8080" }
        val selected = selector.select(URI("https://api.open-meteo.com/v1/forecast"))
        assertEquals(1, selected.size)
        val proxy = selected.first()
        assertEquals(Proxy.Type.HTTP, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals("proxy.local", address.hostString)
        assertEquals(8080, address.port)
    }

    @Test
    fun `selector re-reads the pref on every call`() {
        var raw = ""
        val selector = LiveProxySelector { raw }
        assertSame(Proxy.NO_PROXY, selector.select(URI("https://api.open-meteo.com")).first())
        raw = "socks5://proxy.local:1080"
        val proxy = selector.select(URI("https://api.open-meteo.com")).first()
        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `authenticator answers a proxy challenge with basic credentials`() {
        val authenticator = proxyAuthenticator { "alice:s3cret@proxy.local:3128" }
        val out = authenticator.authenticate(null, response(407, "Proxy Authentication Required"))
        assertNotNull(out)
        assertTrue(out!!.header("Proxy-Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `authenticator ignores an origin server challenge`() {
        val authenticator = proxyAuthenticator { "alice:s3cret@proxy.local:3128" }
        assertNull(authenticator.authenticate(null, response(401, "Unauthorized")))
    }

    @Test
    fun `authenticator ignores a blank pref and an already-authenticated request`() {
        val blank = proxyAuthenticator { "" }
        assertNull(blank.authenticate(null, response(407, "Proxy Authentication Required")))

        val configured = proxyAuthenticator { "alice:s3cret@proxy.local:3128" }
        val retried = response(407, "Proxy Authentication Required").newBuilder()
            .request(
                Request.Builder()
                    .url("http://example.com/")
                    .header("Proxy-Authorization", "Basic d3Jvbmc=")
                    .build(),
            )
            .build()
        assertNull(configured.authenticate(null, retried))
    }

    private fun response(code: Int, message: String): Response = Response.Builder()
        .request(Request.Builder().url("http://example.com/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .build()
}
