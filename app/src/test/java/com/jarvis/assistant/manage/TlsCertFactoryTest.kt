package com.jarvis.assistant.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

/**
 * JVM coverage for the pure (Android-free) half of the R13 §14 TLS spike:
 * `TlsCertFactory` must produce a self-signed cert carrying the loopback SANs,
 * a PKCS#12 keystore the JSSE can actually consume, and a SHA-256 fingerprint.
 *
 * This is what the unit gate can prove without a device; the HTTPS handshake
 * itself is device-only (`androidTest/.../ManagementHttpsSpikeTest`).
 */
class TlsCertFactoryTest {

    @Test
    fun generatedCertificateIsSelfSignedWithLoopbackSansAndAUsableKeystore() {
        val material = TlsCertFactory.generate()

        val cert = material.certificate
        assertEquals(
            "subject and issuer must be identical for a self-signed cert",
            cert.subjectX500Principal.name,
            cert.issuerX500Principal.name,
        )

        val sans = cert.subjectAlternativeNames.orEmpty().map { it[1].toString() }
        assertTrue("SAN must include localhost, got $sans", sans.contains("localhost"))
        assertTrue("SAN must include 127.0.0.1, got $sans", sans.contains("127.0.0.1"))

        assertTrue("keystore must hold the key alias", material.keyStore.isKeyEntry(material.alias))
        assertNotNull("keystore must expose the certificate", material.keyStore.getCertificate(material.alias))

        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(material.keyStore, material.keyPassword) }
            .keyManagers
        assertTrue("JSSE must accept the keystore and yield key managers", keyManagers.isNotEmpty())
    }

    @Test
    fun fingerprintIsTheColonSeparatedSha256OfTheDerCertificate() {
        val fingerprint = TlsCertFactory.generate().sha256Fingerprint

        val parts = fingerprint.split(":")
        assertEquals("SHA-256 has 32 bytes", 32, parts.size)
        assertTrue(
            "each byte must be two uppercase hex digits, got $fingerprint",
            parts.all { it.length == 2 && it == it.uppercase() },
        )
    }

    @Test
    fun `encode then decode reproduces the identical certificate`() {
        val original = TlsCertFactory.generate()

        val decoded = TlsCertFactory.decode(TlsCertFactory.encode(original), original.keyPassword)

        assertNotNull("a valid blob must decode", decoded)
        assertEquals(
            "the SHA-256 fingerprint must survive an encode/decode round trip",
            original.sha256Fingerprint,
            decoded!!.sha256Fingerprint,
        )
    }

    @Test
    fun `decode returns null for corrupt truncated wrong-password and missing-alias input`() {
        val material = TlsCertFactory.generate()
        val encoded = TlsCertFactory.encode(material)

        assertNull("non-Base64 input", TlsCertFactory.decode("not base64!!", material.keyPassword))
        assertNull(
            "a truncated blob",
            TlsCertFactory.decode(encoded.substring(0, encoded.length / 2), material.keyPassword),
        )
        assertNull(
            "a wrong password",
            TlsCertFactory.decode(encoded, "definitely-not-it".toCharArray()),
        )
        assertNull(
            "a keystore whose entry is under another alias",
            TlsCertFactory.decode(TlsCertFactory.encode(foreignAliasMaterial(material)), material.keyPassword),
        )
    }

    /** The same key/cert pair, stored under an alias `decode` does not look for. */
    private fun foreignAliasMaterial(base: TlsMaterial): TlsMaterial {
        val key = base.keyStore.getKey(base.alias, base.keyPassword)
        val chain = base.keyStore.getCertificateChain(base.alias)
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("not-the-management-alias", key, base.keyPassword, chain)
        }
        return TlsMaterial(base.certificate, store, "not-the-management-alias", base.keyPassword)
    }
}
