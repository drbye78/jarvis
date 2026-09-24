package com.jarvis.assistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Principal
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSessionContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * P1-X (audit decision #4) routing unit tests.
 *
 * The bundled Минцифры anchors must be reachable ONLY for `sber.ru` /
 * `*.sber.ru` / `sberbank.ru` / `*.sberbank.ru`; every other peer — and every
 * peer whose host cannot be determined — must be validated strictly against the
 * platform store, with NO cross-branch fallback in either direction.
 *
 * Two recording [X509TrustManager] fakes stand in for the anchor sets, so the
 * assertions are about ROUTING (which set was asked) rather than about PKIX
 * details. Production-anchored end-to-end cases live in [SberTrustTest].
 */
class SberHostScopedTrustManagerTest {

    private class RecordingAnchors(
        val name: String,
        private val reject: Boolean,
        private val issuers: List<X509Certificate> = emptyList(),
    ) : X509TrustManager {
        val serverChecks = mutableListOf<String>()
        val clientChecks = mutableListOf<String>()

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            serverChecks += "${chain.size}/$authType"
            if (reject) throw CertificateException("$name rejects")
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            clientChecks += "${chain.size}/$authType"
            if (reject) throw CertificateException("$name rejects client")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = issuers.toTypedArray()
    }

    /** Real bundled anchors used purely as X.509 fixtures for the fakes. */
    private val russianIssuers: List<X509Certificate> = SberTrust.russianTrustManager()
        .acceptedIssuers
        .toList()

    private val chain: Array<X509Certificate> = russianIssuers.toTypedArray()

    private val acceptingPlatform = RecordingAnchors("platform", reject = false)
    private val rejectingPlatform = RecordingAnchors("platform", reject = true)
    private val acceptingSber = RecordingAnchors("sber", reject = false, russianIssuers)
    private val rejectingSber = RecordingAnchors("sber", reject = true, russianIssuers)

    private fun wrapper(
        sber: X509TrustManager,
        platform: X509TrustManager,
    ) = SberHostScopedTrustManager(sber, platform)

    /**
     * A real [SSLEngine] whose peerHost is [host] and whose handshake session is
     * absent — the shape JSSE presents on the engine path. Measured on JDK 17:
     * `createSSLEngine(host, port).handshakeSession` is null before the
     * handshake, so the wrapper must fall through to `engine.peerHost`.
     */
    private fun engineFor(host: String?): SSLEngine {
        val ctx = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(NoopTrustManager), SecureRandom())
        }
        return ctx.createSSLEngine(host, 443)
    }

    private object NoopTrustManager : X509TrustManager {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * Minimal [ExtendedSSLSession] — only the peer-name getters matter to the
     * wrapper; everything else exists to satisfy the interface and throws on use.
     */
    private class FakeSession(
        private val host: String?,
        private val sni: List<SNIServerName> = emptyList(),
        private val sniUnsupported: Boolean = false,
    ) : ExtendedSSLSession() {
        override fun getRequestedServerNames(): List<SNIServerName> {
            if (sniUnsupported) throw UnsupportedOperationException("role has no SNI")
            return sni
        }

        override fun getPeerHost(): String? = host
        override fun getPeerPort(): Int = 443
        override fun getPacketBufferSize(): Int = 0
        override fun getApplicationBufferSize(): Int = 0
        override fun getCipherSuite(): String = "TLS_NULL_WITH_NULL_NULL"
        override fun getProtocol(): String = "TLSv1.3"
        override fun getId(): ByteArray = ByteArray(0)
        override fun getSessionContext(): SSLSessionContext? = null
        override fun getCreationTime(): Long = 0L
        override fun getLastAccessedTime(): Long = 0L
        override fun invalidate() = Unit
        override fun isValid(): Boolean = false
        override fun putValue(name: String, value: Any?) = Unit
        override fun getValue(name: String): Any? = null
        override fun removeValue(name: String) = Unit
        override fun getValueNames(): Array<String> = emptyArray()
        override fun getPeerCertificates(): Array<Certificate> =
            throw SSLPeerUnverifiedException("no handshake")

        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getPeerPrincipal(): Principal = throw SSLPeerUnverifiedException("no peer")
        override fun getLocalPrincipal(): Principal? = null
        override fun getLocalSupportedSignatureAlgorithms(): Array<String> = emptyArray()
        override fun getPeerSupportedSignatureAlgorithms(): Array<String> = emptyArray()

        @Suppress("DEPRECATION")
        override fun getPeerCertificateChain(): Array<javax.security.cert.X509Certificate> =
            emptyArray()
    }

    private fun hostName(session: SSLSession?, enginePeerHost: String? = null): String? =
        SberTrust.peerHostName(session, enginePeerHost)

    // ---- routing ------------------------------------------------------------

    @Test
    fun `sber ru host is validated against the sber anchors only`() {
        val w = wrapper(acceptingSber, rejectingPlatform)
        w.checkServerTrusted(chain, "RSA", engineFor("smartspeech.sber.ru"))
        assertEquals(listOf("${chain.size}/RSA"), acceptingSber.serverChecks)
        assertTrue("platform anchors must not be consulted", rejectingPlatform.serverChecks.isEmpty())
    }

    @Test
    fun `sberbank ru host is validated against the sber anchors only`() {
        val w = wrapper(acceptingSber, rejectingPlatform)
        w.checkServerTrusted(chain, "RSA", engineFor("gigachat.devices.sberbank.ru"))
        assertEquals(1, acceptingSber.serverChecks.size)
        assertTrue(rejectingPlatform.serverChecks.isEmpty())
    }

    @Test
    fun `api giga chat is validated against the sber anchors`() {
        // The unified GigaChat v2 endpoint chains to the Минцифры Sub CA; without
        // giga.chat in the allowlist the native client cannot reach it on API 29.
        val w = wrapper(acceptingSber, rejectingPlatform)
        w.checkServerTrusted(chain, "RSA", engineFor("api.giga.chat"))
        assertEquals(listOf("${chain.size}/RSA"), acceptingSber.serverChecks)
        assertTrue("platform anchors must not be consulted", rejectingPlatform.serverChecks.isEmpty())
    }

    @Test
    fun `look-alike host is validated against the platform anchors only`() {
        val w = wrapper(acceptingSber, acceptingPlatform)
        w.checkServerTrusted(chain, "RSA", engineFor("sber.ru.attacker.com"))
        assertEquals(1, acceptingPlatform.serverChecks.size)
        assertTrue(" Минцифры anchors must never be offered to a look-alike", acceptingSber.serverChecks.isEmpty())
    }

    @Test
    fun `platform rejection is never rescued by the mincifry branch`() {
        val w = wrapper(acceptingSber, rejectingPlatform)
        assertThrows(CertificateException::class.java) {
            w.checkServerTrusted(chain, "RSA", engineFor("api.openai.com"))
        }
        assertEquals(1, rejectingPlatform.serverChecks.size)
        assertTrue("no cross-branch fallback allowed", acceptingSber.serverChecks.isEmpty())
    }

    @Test
    fun `sber rejection is never rescued by the platform branch`() {
        val w = wrapper(rejectingSber, acceptingPlatform)
        assertThrows(CertificateException::class.java) {
            w.checkServerTrusted(chain, "RSA", engineFor("ngw.devices.sberbank.ru"))
        }
        assertEquals(1, rejectingSber.serverChecks.size)
        assertTrue("no cross-branch fallback allowed", acceptingPlatform.serverChecks.isEmpty())
    }

    @Test
    fun `a plain no-context check has no host so it takes the platform branch`() {
        val w = wrapper(acceptingSber, acceptingPlatform)
        w.checkServerTrusted(chain, "RSA")
        assertEquals(1, acceptingPlatform.serverChecks.size)
        assertTrue(acceptingSber.serverChecks.isEmpty())
    }

    @Test
    fun `an ip literal peer host takes the platform branch`() {
        val w = wrapper(acceptingSber, acceptingPlatform)
        w.checkServerTrusted(chain, "RSA", engineFor("127.0.0.1"))
        assertEquals(1, acceptingPlatform.serverChecks.size)
        assertTrue(acceptingSber.serverChecks.isEmpty())
    }

    @Test
    fun `a non sber engine with a null peer host takes the platform branch`() {
        val w = wrapper(acceptingSber, acceptingPlatform)
        w.checkServerTrusted(chain, "RSA", engineFor(null))
        assertEquals(1, acceptingPlatform.serverChecks.size)
        assertTrue(acceptingSber.serverChecks.isEmpty())
    }

    @Test
    fun `client-side checks follow the same routing`() {
        val w = wrapper(acceptingSber, acceptingPlatform)
        w.checkClientTrusted(chain, "RSA", engineFor("smartspeech.sber.ru"))
        w.checkClientTrusted(chain, "RSA", engineFor("example.com"))
        w.checkClientTrusted(chain, "RSA")
        assertEquals("only the Sber peer reached the bundled branch", 1, acceptingSber.clientChecks.size)
        assertEquals("the other two had no Sber host", 2, acceptingPlatform.clientChecks.size)
        assertTrue(acceptingSber.serverChecks.isEmpty())
        assertTrue(acceptingPlatform.serverChecks.isEmpty())
    }

    @Test
    fun `accepted issuers are the deduplicated union of both branches`() {
        val w = wrapper(acceptingSber, acceptingPlatform)
        assertEquals(
            "both anchor sets must be listed (chain building happens before the host is known)",
            russianIssuers.size,
            w.acceptedIssuers.distinct().size,
        )
        assertEquals(russianIssuers.size, w.acceptedIssuers.size)
    }

    @Test
    fun `the installed composite is the host-scoped wrapper`() {
        val tm = SberTrust.compositeTrustManager()
        assertTrue(
            "AppGraph installs compositeTrustManager(): it must BE the wrapper, or host scoping is dead code",
            tm is SberHostScopedTrustManager,
        )
        assertTrue(
            "the Sber branch must still expose the bundled root",
            tm.acceptedIssuers.any { it.subjectX500Principal.name.contains("Russian Trusted Root CA") },
        )
    }

    // ---- peer-host extraction (SNI first) ----------------------------------

    @Test
    fun `SNI wins over a numeric handshake peer host`() {
        // Measured on JDK 17 with OkHttp's socket shape: handshakeSession.peerHost
        // is the numeric address while SNI carries the real name. Without this
        // precedence every Sber handshake would be treated as NON-Sber.
        val session = FakeSession(
            host = "127.0.0.1",
            sni = listOf(SNIHostName("smartspeech.sber.ru")),
        )
        assertEquals("smartspeech.sber.ru", hostName(session))
        assertTrue(SberTrust.isSberHost(hostName(session)))
    }

    @Test
    fun `the last requested server name is the one used`() {
        val session = FakeSession(
            host = null,
            sni = listOf(SNIHostName("ignored.example"), SNIHostName("gigachat.devices.sberbank.ru")),
        )
        assertEquals("gigachat.devices.sberbank.ru", hostName(session))
    }

    @Test
    fun `a numeric peer host with no SNI yields no usable host`() {
        val session = FakeSession(host = "127.0.0.1")
        assertEquals(null, hostName(session))
        assertEquals(null, hostName(session, enginePeerHost = "127.0.0.1"))
        assertTrue("an unknown host must never be scoped as Sber", !SberTrust.isSberHost(hostName(session)))
    }

    @Test
    fun `a dns handshake peer host is used when no SNI is present`() {
        assertEquals("api.openai.com", hostName(FakeSession(host = "api.openai.com")))
    }

    @Test
    fun `the handshake session outranks the engine creation host`() {
        val session = FakeSession(host = "api.openai.com")
        assertEquals(
            "a live handshake session is more authoritative than engine creation params",
            "api.openai.com",
            hostName(session, enginePeerHost = "sber.ru"),
        )
    }

    @Test
    fun `a session that refuses to report SNI degrades instead of throwing`() {
        // Conscrypt's extended-session getters are allowed to throw; a throw that
        // escaped here would abort every handshake on the device.
        val session = FakeSession(host = null, sniUnsupported = true)
        assertEquals(
            "gigachat.devices.sberbank.ru",
            hostName(session, enginePeerHost = "gigachat.devices.sberbank.ru"),
        )
    }

    @Test
    fun `a null session falls back to the engine peer host`() {
        assertEquals("sber.ru", hostName(null, enginePeerHost = "sber.ru"))
        assertEquals(null, hostName(null, enginePeerHost = "  "))
    }

    // ---- host matching ------------------------------------------------------

    @Test
    fun `isSberHost accepts the apexes and every subdomain`() {
        listOf(
            "sber.ru",
            "SBER.RU",
            "sber.ru.",
            " smartspeech.sber.ru ",
            "ngw.devices.sberbank.ru",
            "a.b.c.sberbank.ru",
        ).forEach {
            assertTrue("must be Sber: $it", SberTrust.isSberHost(it))
        }
    }

    @Test
    fun `isSberHost rejects look-alikes and non-DNS hosts`() {
        listOf(
            "notsber.ru",
            "sber.ru.attacker.com",
            "sberbank.ru.evil.net",
            "sber.ru:443",
            "https://sber.ru",
            "evil-sberbank.ru",
            "sber.ru.example",
            "127.0.0.1",
            "::1",
            "[::1]",
            "localhost",
            "",
            "   ",
        ).forEach {
            assertTrue("must NOT be Sber: $it", !SberTrust.isSberHost(it))
        }
        assertTrue("null is never Sber", !SberTrust.isSberHost(null))
    }

    @Test
    fun `ip literal detection is textual and conservative`() {
        listOf("1.2.3.4", "10.0.0.1", "::1", "[::1]", "fe80::1%eth0").forEach {
            assertTrue("expected an IP literal: $it", SberTrust.isIpLiteral(it))
        }
        listOf("sber.ru", "localhost", "1.2.3", "example.com", "1.2.3.4.5").forEach {
            assertTrue("expected a non-IP host: $it", !SberTrust.isIpLiteral(it))
        }
    }
}
