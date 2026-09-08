package com.jarvis.assistant.util

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

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
}
