package com.jarvis.assistant.manage

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.Locale

/**
 * The ephemeral TLS material for the R13 §14 management server: a self-signed
 * certificate, the [KeyStore] that holds its private key, and the alias /
 * password Ktor's `sslConnector` needs to load them.
 *
 * [keyPassword] is a `CharArray` on purpose (not a `String`) so the caller can
 * zero it once the connector has read it; it cannot be a `data class` member
 * because array equality would be meaningless.
 */
class TlsMaterial(
    val certificate: X509Certificate,
    val keyStore: KeyStore,
    val alias: String,
    val keyPassword: CharArray,
) {
    /** Uppercase colon-separated SHA-256 of the DER cert, as shown in the app UI. */
    val sha256Fingerprint: String
        get() = TlsCertFactory.sha256Fingerprint(certificate)
}

/**
 * Builds the self-signed cert for the (disabled-by-default) management server.
 *
 * Why BouncyCastle: Android has **no public certificate-builder API** —
 * `sun.security.x509` is absent — so `JcaX509v3CertificateBuilder` /
 * `JcaX509CertificateConverter` are the only supported route. Ktor's
 * `buildKeyStore` is deliberately NOT used: it is documented testing-only
 * (1024-bit / SHA-1 / 3-day), unsuitable for a real TLS endpoint.
 *
 * Provider policy: the BC classes are used **directly, with no global provider
 * registration**. Android already registers a provider named `BC` pointing at
 * its repackaged `com.android.org.bouncycastle`, so calling
 * `Security.addProvider(BouncyCastleProvider())` here would collide with it.
 * `SHA256withRSA` is available from the platform provider on API 29 (and from
 * SunRsaSign on the JVM), so [JcaContentSignerBuilder] and
 * [JcaX509CertificateConverter] resolve the algorithm without a registered
 * external BC provider.
 *
 * This file is deliberately Android-free (pure JCE + BC) so it is unit-testable
 * on the JVM; only the device diagnostic exercises it under Android's providers.
 */
object TlsCertFactory {

    /** Alias under which the generated key/cert pair is stored. */
    const val KEY_ALIAS = "jarvis-management"

    private const val VALIDITY_DAYS = 825L
    private const val KEY_SIZE_BITS = 2048
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    private const val PASSWORD_LENGTH = 24

    /**
     * Unambiguous alphabet (no O/0, I/l) — matches the R13 password convention
     * even though this ephemeral password is never shown to a user.
     */
    private const val PASSWORD_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    /**
     * Generates a fresh RSA-2048 self-signed cert valid for ~825 days, with
     * `localhost` + `127.0.0.1` in the SAN (the LAN IP is appended here once the
     * LAN mode exists), and a PKCS#12 keystore holding the private key.
     *
     * The password is random by default; pass one explicitly only in tests.
     */
    fun generate(
        commonName: String = "Jarvis Management",
        dnsNames: List<String> = listOf("localhost"),
        ipAddresses: List<String> = listOf("127.0.0.1"),
        password: CharArray = randomPassword(),
        now: Date = Date(),
    ): TlsMaterial {
        val keyPair = rsaKeyPair()
        val certificate = selfSign(keyPair, commonName, dnsNames, ipAddresses, now)
        val keyStore = keyStore(keyPair.private, certificate, password)
        return TlsMaterial(certificate, keyStore, KEY_ALIAS, password)
    }

    /**
     * Uppercase, colon-separated SHA-256 fingerprint of the certificate's DER
     * form (e.g. `AB:CD:...`), suitable for visual verification of the browser
     * warning. `Locale.US` is explicit because the detekt ImplicitDefaultLocale
     * rule is active.
     */
    fun sha256Fingerprint(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString(separator = ":") { String.format(Locale.US, "%02X", it) }
    }

    private fun rsaKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("RSA")
            .apply { initialize(KEY_SIZE_BITS, SecureRandom()) }
            .generateKeyPair()

    private fun randomPassword(): CharArray {
        val random = SecureRandom()
        return CharArray(PASSWORD_LENGTH) { PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)] }
    }

    private fun selfSign(
        keyPair: KeyPair,
        commonName: String,
        dnsNames: List<String>,
        ipAddresses: List<String>,
        now: Date,
    ): X509Certificate {
        val subject = X500Name("CN=$commonName, O=Jarvis")
        val notAfter = Date(now.time + VALIDITY_DAYS * MILLIS_PER_DAY)
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            now,
            notAfter,
            subject,
            keyPair.public,
        )
        // Not a CA: this cert signs nothing but itself.
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        val names = dnsNames.map { GeneralName(GeneralName.dNSName, it) } +
            ipAddresses.map { GeneralName(GeneralName.iPAddress, it) }
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }

    private fun keyStore(
        privateKey: PrivateKey,
        certificate: X509Certificate,
        password: CharArray,
    ): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry(KEY_ALIAS, privateKey, password, arrayOf(certificate))
    }
}
