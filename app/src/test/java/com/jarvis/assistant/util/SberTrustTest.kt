package com.jarvis.assistant.util

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
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
        extendedComposite().checkServerTrusted(bundledChain, "RSA", engineFor("smartspeech.sber.ru"))
        extendedComposite()
            .checkServerTrusted(bundledChain, "RSA", engineFor("ngw.devices.sberbank.ru"))
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
}
