package com.jarvis.assistant.util

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * SberTrust: the composite trust manager must (a) parse the bundled
 * Минцифры CAs, (b) accept chains the system store rejects when they are
 * signed by the bundled hierarchy, (c) still REJECT garbage — adding a
 * fallback CA must never mean skipping validation.
 */
class SberTrustTest {

    private fun loadCertResource(name: String): X509Certificate {
        val pem = javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.inputStream()) as X509Certificate
    }

    @Test
    fun `bundled CAs parse and are the Минцифры hierarchy`() {
        val tm = SberTrust.russianTrustManager()
        val issuers = tm.acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(
            "root CA missing: $issuers",
            issuers.any { it.contains("Russian Trusted Root CA") },
        )
        assertTrue(
            "sub CA missing: $issuers",
            issuers.any { it.contains("Russian Trusted Sub CA") },
        )
    }

    @Test
    fun `composite manager exposes both system and russian issuers`() {
        val tm = SberTrust.compositeTrustManager()
        val issuers = tm.acceptedIssuers.map { it.subjectX500Principal.name }
        assertTrue(issuers.isNotEmpty())
        assertTrue(issuers.any { it.contains("Russian Trusted Root CA") })
    }

    @Test
    fun `composite manager still rejects an empty chain`() {
        val tm = SberTrust.compositeTrustManager()
        try {
            // Per the X509TrustManager contract an empty chain is a caller
            // error: the platform managers throw IllegalArgumentException
            // (NOT CertificateException), and the composite must not launder
            // it into a validation pass.
            tm.checkServerTrusted(emptyArray(), "RSA")
            throw AssertionError("empty chain must not validate")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun `composite manager rejects a self-signed cert not in either store`() {
        val garbage = loadCertResource("garbage-self-signed.pem")
        val tm = SberTrust.compositeTrustManager()
        try {
            tm.checkServerTrusted(arrayOf(garbage), "RSA")
            throw AssertionError("unknown self-signed cert must not validate")
        } catch (expected: CertificateException) {
        }
    }

    @Test
    fun `okhttp hardening produces a client with the custom ssl factory`() {
        val client: OkHttpClient = OkHttpClient().withSberTrust()
        assertNotNull(client.sslSocketFactory)
        assertNotNull(client.x509TrustManager)
        assertEquals(
            "Russian Trusted Root CA",
            client.x509TrustManager!!.acceptedIssuers
                .first { it.subjectX500Principal.name.contains("Russian Trusted Root CA") }
                .subjectX500Principal.name
                .substringAfter("CN=")
                .substringBefore(","),
        )
    }

    // ---- decision #4, end to end with the PRODUCTION anchors ----------------

    private val bundledChain: Array<X509Certificate> by lazy {
        val anchors = SberTrust.russianTrustManager().acceptedIssuers
        arrayOf(
            // Leaf-first, the order JSSE hands over: sub CA under the root.
            anchors.first { it.subjectX500Principal.name.contains("Russian Trusted Sub CA") },
            anchors.first { it.subjectX500Principal.name.contains("Russian Trusted Root CA") },
        )
    }

    private fun extendedComposite(): X509ExtendedTrustManager {
        val tm = SberTrust.compositeTrustManager()
        assertTrue(
            "the installed manager must be host-aware (X509ExtendedTrustManager) or JSSE " +
                "will only ever call the context-free overload",
            tm is X509ExtendedTrustManager,
        )
        return tm as X509ExtendedTrustManager
    }

    /** A real engine carrying [host] as its peer host — the SSLEngine handshake shape. */
    private fun engineFor(host: String?): SSLEngine =
        SSLContext.getInstance("TLS").apply { init(null, null, SecureRandom()) }
            .createSSLEngine(host, 443)

    @Test
    fun `the mincifry chain still validates a sber host`() {
        // The composite's context-free overload is the path a caller with no
        // handshake session uses, and it must accept the bundled Минцифры
        // chain via its system-first → russian fallback. The host-scoped
        // wrapper's 3-arg path forwards the live engine/socket, so it cannot
        // be exercised with a synthetic engine on the JVM — the Sber-host
        // ROUTING is pinned by the fake-anchor tests further down.
        SberTrust.sberCompositeTrustManager().checkServerTrusted(bundledChain, "RSA")
    }

    @Test
    fun `the mincifry chain is REJECTED for any other host`() {
        // This is the hole decision #4 closes: before the fix the composite
        // offered the bundled anchors to EVERY host, so a Минцифры-issued cert
        // was a valid credential for the user-configurable OpenAI base URL too.
        val tm = extendedComposite()
        listOf("api.openai.com", "sber.ru.attacker.com", "notsber.ru").forEach { host ->
            try {
                tm.checkServerTrusted(bundledChain, "RSA", engineFor(host))
                throw AssertionError("Минцифры anchors must not validate $host")
            } catch (expected: CertificateException) {
            }
        }
    }

    @Test
    fun `handshakes with no resolvable host reject the mincifry chain`() {
        val tm = extendedComposite()
        try {
            tm.checkServerTrusted(bundledChain, "RSA")
            throw AssertionError("no peer host => platform-only anchors")
        } catch (expected: CertificateException) {
        }
        // The socket overload with an unconnected socket exposes no host at all.
        val plainSocket = SberTrust.sslContext().socketFactory.createSocket()
        try {
            tm.checkServerTrusted(bundledChain, "RSA", plainSocket)
            throw AssertionError("unknown socket peer => platform-only anchors")
        } catch (expected: CertificateException) {
        }
    }

    // ---- 3-arg forwarding (the Android per-domain regression) ---------------
    //
    // Android's platform `RootTrustManager` 2-arg method throws a
    // CertificateException as soon as the app declares ANY `<domain-config>`
    // (`res/xml/network_security_config.xml` has the loopback MCP exception):
    //   "Domain specific configurations require that hostname aware
    //    checkServerTrusted(X509Certificate[], String, String) is used"
    // The old wrapper collapsed every 3-arg call to the 2-arg form, so every
    // non-Sber host through the shared OkHttp client failed the handshake. The
    // JVM default manager never per-domain-throws, which is exactly why the
    // suite below has to simulate the Android behavior.

    /**
     * The Android `RootTrustManager` shape: 2-arg server checks always throw
     * the per-domain exception, 3-arg checks validate and record the live
     * context.
     */
    private class AndroidPlatformAnchors(
        private val serverOk: Boolean = false,
    ) : X509ExtendedTrustManager() {
        var engineServerChecks = 0
            private set
        var socketServerChecks = 0
            private set
        var twoArgServerChecks = 0
            private set
        var clientChecks = 0
            private set
        var lastEngine: SSLEngine? = null
            private set
        var lastSocket: Socket? = null
            private set

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            twoArgServerChecks++
            throw CertificateException(
                "Domain specific configurations require that hostname aware " +
                    "checkServerTrusted(X509Certificate[], String, String) is used",
            )
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            engineServerChecks++
            lastEngine = engine
            if (!serverOk) throw CertificateException("platform 3-arg rejects")
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            socketServerChecks++
            lastSocket = socket
            if (!serverOk) throw CertificateException("platform 3-arg rejects")
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            clientChecks++
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            clientChecks++
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            clientChecks++
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** A recording anchor set that accepts or rejects by configuration. */
    private class ConfigurableAnchors(
        private val accept: Boolean,
    ) : X509ExtendedTrustManager() {
        var engineServerChecks = 0
            private set
        var socketServerChecks = 0
            private set
        var twoArgServerChecks = 0
            private set
        var clientChecks = 0
            private set
        var lastEngine: SSLEngine? = null
            private set

        private fun decide() {
            if (!accept) throw CertificateException("anchors reject")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            twoArgServerChecks++
            decide()
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            engineServerChecks++
            lastEngine = engine
            decide()
        }

        override fun checkServerTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            socketServerChecks++
            decide()
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            clientChecks++
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            socket: Socket?,
        ) {
            clientChecks++
        }

        override fun checkClientTrusted(
            chain: Array<X509Certificate>,
            authType: String,
            engine: SSLEngine?,
        ) {
            clientChecks++
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun wrapper(
        sber: X509ExtendedTrustManager,
        platform: X509ExtendedTrustManager,
    ): SberHostScopedTrustManager = SberHostScopedTrustManager(sber, platform)

    @Test
    fun `non-sber host forwards the original engine to the platform 3-arg overload`() {
        val platform = AndroidPlatformAnchors(serverOk = false)
        val sber = ConfigurableAnchors(accept = true)
        val tm = wrapper(sber, platform)
        val engine = engineFor("api.openai.com")

        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA", engine)
        }

        assertEquals("the platform 3-arg overload must be used", 1, platform.engineServerChecks)
        assertSame("the EXACT engine instance must be forwarded", engine, platform.lastEngine)
        assertEquals("the platform 2-arg overload must never be used", 0, platform.twoArgServerChecks)
        assertEquals("a non-Sber host must never reach the Sber anchors", 0, sber.engineServerChecks)
    }

    @Test
    fun `non-sber host forwards the original socket to the platform 3-arg overload`() {
        val platform = AndroidPlatformAnchors(serverOk = false)
        val sber = ConfigurableAnchors(accept = true)
        val tm = wrapper(sber, platform)
        val socket = SberTrust.sslContext().socketFactory.createSocket()

        assertThrows(CertificateException::class.java) {
            tm.checkServerTrusted(emptyArray(), "RSA", socket)
        }

        assertEquals("the platform 3-arg overload must be used", 1, platform.socketServerChecks)
        assertSame("the EXACT socket instance must be forwarded", socket, platform.lastSocket)
        assertEquals("a non-Sber host must never reach the Sber anchors", 0, sber.socketServerChecks)
    }

    @Test
    fun `sber host routes to the sber anchors and forwards the original engine`() {
        val platform = AndroidPlatformAnchors(serverOk = false)
        val sber = ConfigurableAnchors(accept = true)
        val tm = wrapper(sber, platform)
        val engine = engineFor("smartspeech.sber.ru")

        tm.checkServerTrusted(emptyArray(), "RSA", engine)

        assertEquals(1, sber.engineServerChecks)
        assertSame(engine, sber.lastEngine)
        assertEquals("the platform anchors must not be consulted", 0, platform.engineServerChecks)
    }

    @Test
    fun `non-sber host forwards the original engine to the platform client 3-arg overload`() {
        val platform = AndroidPlatformAnchors(serverOk = false)
        val sber = ConfigurableAnchors(accept = true)
        val tm = wrapper(sber, platform)
        val engine = engineFor("api.openai.com")

        tm.checkClientTrusted(emptyArray(), "RSA", engine)

        assertEquals(1, platform.clientChecks)
        assertEquals("a non-Sber host must never reach the Sber anchors", 0, sber.clientChecks)
        assertEquals("server overloads must be untouched", 0, platform.engineServerChecks)
    }

    @Test
    fun `composite system-first branch is reachable through the 3-arg path`() {
        val system = ConfigurableAnchors(accept = true)
        val russian = ConfigurableAnchors(accept = false)
        val composite = SberTrust.sberCompositeTrustManager(system, russian)
        val engine = engineFor("ngw.devices.sberbank.ru")

        // Must NOT throw: the system 3-arg validates before the Минцифры
        // fallback is even considered (the latent second defect).
        composite.checkServerTrusted(emptyArray(), "RSA", engine)

        assertEquals(1, system.engineServerChecks)
        assertSame(engine, system.lastEngine)
        assertEquals("system success must not consult the fallback", 0, russian.engineServerChecks)
        assertEquals("must not degrade to the 2-arg Android trap", 0, system.twoArgServerChecks)
    }

    @Test
    fun `composite falls back to the mincifry anchors with the original engine`() {
        val system = ConfigurableAnchors(accept = false)
        val russian = ConfigurableAnchors(accept = true)
        val composite = SberTrust.sberCompositeTrustManager(system, russian)
        val engine = engineFor("ngw.devices.sberbank.ru")

        composite.checkServerTrusted(emptyArray(), "RSA", engine)

        assertEquals(1, system.engineServerChecks)
        assertEquals(1, russian.engineServerChecks)
        assertSame("the fallback must receive the ORIGINAL context", engine, russian.lastEngine)
    }
}
