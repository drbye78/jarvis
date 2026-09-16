package com.jarvis.assistant.util

import okhttp3.OkHttpClient
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Trust configuration for the Sber endpoints (OAuth, GigaChat, Salute
 * Speech gRPC). Their certificates chain to the Минцифры "Russian Trusted
 * Root CA" hierarchy, which is NOT present in the stock Android trust store
 * (the wall device runs Android 10 / API 29) — without this, every
 * Sber-facing HTTPS/gRPC call fails with
 * `SSLHandshakeException: Trust anchor for certification path not found`.
 *
 * ## The bundled anchors are HOST-SCOPED (audit decision #4)
 *
 * [compositeTrustManager] — the one every client installs — is a
 * [SberHostScopedTrustManager] wrapper: the Минцифры fallback is reachable
 * ONLY for peers whose handshake host is `sber.ru` / `*.sber.ru` /
 * `sberbank.ru` / `*.sberbank.ru`. Every other host (including the
 * user-configurable base URL of [com.jarvis.assistant.llm.OpenAiCompatClient],
 * which shares this OkHttp client) is validated STRICTLY against the platform
 * trust store, so a Минцифry-issued cert is never a valid credential for an
 * unrelated endpoint. No validation is ever skipped on any path, and a
 * rejection propagates (no cross-branch fallback).
 *
 * Hostname↔certificate binding is untouched: JSSE endpoint identification /
 * OkHttp's hostname verifier still match the peer certificate against the
 * requested host; this class only decides WHICH anchor set may validate the
 * chain.
 *
 * The certificates are the official ones published for public download at
 * https://gu-st.ru (Госуслуги / Минцифры), embedded verbatim so the code
 * stays Context-free. Update procedure: replace the two PEM constants with
 * the fresh files from the same page (the sub CA expires 2027-03).
 */
object SberTrust {

    /**
     * Apex domains whose endpoints legitimately chain to the bundled
     * Минцифры hierarchy. Covers every default Sber target in
     * [com.jarvis.assistant.config.JarvisConfig]:
     * `ngw.devices.sberbank.ru` (OAuth), `gigachat.devices.sberbank.ru`
     * (GigaChat) and `smartspeech.sber.ru` (Salute Speech gRPC).
     */
    private val SBER_APEX_DOMAINS = listOf("sber.ru", "sberbank.ru")

    /** `javax.net.ssl.SNIServerName.SNI_HOST_NAME` (the constant is absent from the API-34 stubs). */
    private const val SNI_HOST_NAME_TYPE = 0

    private val RUSSIAN_ROOT_PEM = """
-----BEGIN CERTIFICATE-----
MIIFwjCCA6qgAwIBAgICEAAwDQYJKoZIhvcNAQELBQAwcDELMAkGA1UEBhMCUlUx
PzA9BgNVBAoMNlRoZSBNaW5pc3RyeSBvZiBEaWdpdGFsIERldmVsb3BtZW50IGFu
ZCBDb21tdW5pY2F0aW9uczEgMB4GA1UEAwwXUnVzc2lhbiBUcnVzdGVkIFJvb3Qg
Q0EwHhcNMjIwMzAxMjEwNDE1WhcNMzIwMjI3MjEwNDE1WjBwMQswCQYDVQQGEwJS
VTE/MD0GA1UECgw2VGhlIE1pbmlzdHJ5IG9mIERpZ2l0YWwgRGV2ZWxvcG1lbnQg
YW5kIENvbW11bmljYXRpb25zMSAwHgYDVQQDDBdSdXNzaWFuIFRydXN0ZWQgUm9v
dCBDQTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAMfFOZ8pUAL3+r2n
qqE0Zp52selXsKGFYoG0GM5bwz1bSFtCt+AZQMhkWQheI3poZAToYJu69pHLKS6Q
XBiwBC1cvzYmUYKMYZC7jE5YhEU2bSL0mX7NaMxMDmH2/NwuOVRj8OImVa5s1F4U
zn4Kv3PFlDBjjSjXKVY9kmjUBsXQrIHeaqmUIsPIlNWUnimXS0I0abExqkbdrXbX
YwCOXhOO2pDUx3ckmJlCMUGacUTnylyQW2VsJIyIGA8V0xzdaeUXg0VZ6ZmNUr5Y
Ber/EAOLPb8NYpsAhJe2mXjMB/J9HNsoFMBFJ0lLOT/+dQvjbdRZoOT8eqJpWnVD
U+QL/qEZnz57N88OWM3rabJkRNdU/Z7x5SFIM9FrqtN8xewsiBWBI0K6XFuOBOTD
4V08o4TzJ8+Ccq5XlCUW2L48pZNCYuBDfBh7FxkB7qDgGDiaftEkZZfApRg2E+M9
G8wkNKTPLDc4wH0FDTijhgxR3Y4PiS1HL2Zhw7bD3CbslmEGgfnnZojNkJtcLeBH
BLa52/dSwNU4WWLubaYSiAmA9IUMX1/RpfpxOxd4Ykmhz97oFbUaDJFipIggx5sX
ePAlkTdWnv+RWBxlJwMQ25oEHmRguNYf4Zr/Rxr9cS93Y+mdXIZaBEE0KS2iLRqa
OiWBki9IMQU4phqPOBAaG7A+eP8PAgMBAAGjZjBkMB0GA1UdDgQWBBTh0YHlzlpf
BKrS6badZrHF+qwshzAfBgNVHSMEGDAWgBTh0YHlzlpfBKrS6badZrHF+qwshzAS
BgNVHRMBAf8ECDAGAQH/AgEEMA4GA1UdDwEB/wQEAwIBhjANBgkqhkiG9w0BAQsF
AAOCAgEAALIY1wkilt/urfEVM5vKzr6utOeDWCUczmWX/RX4ljpRdgF+5fAIS4vH
tmXkqpSCOVeWUrJV9QvZn6L227ZwuE15cWi8DCDal3Ue90WgAJJZMfTshN4OI8cq
W9E4EG9wglbEtMnObHlms8F3CHmrw3k6KmUkWGoa+/ENmcVl68u/cMRl1JbW2bM+
/3A+SAg2c6iPDlehczKx2oa95QW0SkPPWGuNA/CE8CpyANIhu9XFrj3RQ3EqeRcS
AQQod1RNuHpfETLU/A2gMmvn/w/sx7TB3W5BPs6rprOA37tutPq9u6FTZOcG1Oqj
C/B7yTqgI7rbyvox7DEXoX7rIiEqyNNUguTk/u3SZ4VXE2kmxdmSh3TQvybfbnXV
4JbCZVaqiZraqc7oZMnRoWrXRG3ztbnbes/9qhRGI7PqXqeKJBztxRTEVj8ONs1d
WN5szTwaPIvhkhO3CO5ErU2rVdUr89wKpNXbBODFKRtgxUT70YpmJ46VVaqdAhOZ
D9EUUn4YaeLaS8AjSF/h7UkjOibNc4qVDiPP+rkehFWM66PVnP1Msh93tc+taIfC
EYVMxjh8zNbFuoc7fzvvrFILLe7ifvEIUqSVIC/AzplM/Jxw7buXFeGP1qVCBEHq
391d/9RAfaZ12zkwFsl+IKwE/OZxW8AHa9i1p4GO0YSNuczzEm4=
-----END CERTIFICATE-----
    """.trimIndent()

    private val RUSSIAN_SUB_PEM = """
-----BEGIN CERTIFICATE-----
MIIHQjCCBSqgAwIBAgICEAIwDQYJKoZIhvcNAQELBQAwcDELMAkGA1UEBhMCUlUx
PzA9BgNVBAoMNlRoZSBNaW5pc3RyeSBvZiBEaWdpdGFsIERldmVsb3BtZW50IGFu
ZCBDb21tdW5pY2F0aW9uczEgMB4GA1UEAwwXUnVzc2lhbiBUcnVzdGVkIFJvb3Qg
Q0EwHhcNMjIwMzAyMTEyNTE5WhcNMjcwMzA2MTEyNTE5WjBvMQswCQYDVQQGEwJS
VTE/MD0GA1UECgw2VGhlIE1pbmlzdHJ5IG9mIERpZ2l0YWwgRGV2ZWxvcG1lbnQg
YW5kIENvbW11bmljYXRpb25zMR8wHQYDVQQDDBZSdXNzaWFuIFRydXN0ZWQgU3Vi
IENBMIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEA9YPqBKOk19NFymrE
wehzrhBEgT2atLezpduB24mQ7CiOa/HVpFCDRZzdxqlh8drku408/tTmWzlNH/br
HuQhZ/miWKOf35lpKzjyBd6TPM23uAfJvEOQ2/dnKGGJbsUo1/udKSvxQwVHpVv3
S80OlluKfhWPDEXQpgyFqIzPoxIQTLZ0deirZwMVHarZ5u8HqHetRuAtmO2ZDGQn
vVOJYAjls+Hiueq7Lj7Oce7CQsTwVZeP+XQx28PAaEZ3y6sQEt6rL06ddpSdoTMp
BnCqTbxW+eWMyjkIn6t9GBtUV45yB1EkHNnj2Ex4GwCiN9T84QQjKSr+8f0psGrZ
vPbCbQAwNFJjisLixnjlGPLKa5vOmNwIh/LAyUW5DjpkCx004LPDuqPpFsKXNKpa
L2Dm6uc0x4Jo5m+gUTVORB6hOSzWnWDj2GWfomLzzyjG81DRGFBpco/O93zecsIN
3SL2Ysjpq1zdoS01CMYxie//9zWvYwzI25/OZigtnpCIrcd2j1Y6dMUFQAzAtHE+
qsXflSL8HIS+IJEFIQobLlYhHkoE3avgNx5jlu+OLYe0dF0Ykx1PGNjbwqvTX37R
Cn32NMjlotW2QcGEZhDKj+3urZizp5xdTPZitA+aEjZM/Ni71VOdiOP0igbw6asZ
2fxdozZ1TnSSYNYvNATwthNmZysCAwEAAaOCAeUwggHhMBIGA1UdEwEB/wQIMAYB
Af8CAQAwDgYDVR0PAQH/BAQDAgGGMB0GA1UdDgQWBBTR4XENCy2BTm6KSo9MI7NM
XqtpCzAfBgNVHSMEGDAWgBTh0YHlzlpfBKrS6badZrHF+qwshzCBxwYIKwYBBQUH
AQEEgbowgbcwOwYIKwYBBQUHMAKGL2h0dHA6Ly9yb3N0ZWxlY29tLnJ1L2NkcC9y
b290Y2Ffc3NsX3JzYTIwMjIuY3J0MDsGCCsGAQUFBzAChi9odHRwOi8vY29tcGFu
eS5ydC5ydS9jZHAvcm9vdGNhX3NzbF9yc2EyMDIyLmNydDA7BggrBgEFBQcwAoYv
aHR0cDovL3JlZXN0ci1wa2kucnUvY2RwL3Jvb3RjYV9zc2xfcnNhMjAyMi5jcnQw
gbAGA1UdHwSBqDCBpTA1oDOgMYYvaHR0cDovL3Jvc3RlbGVjb20ucnUvY2RwL3Jv
b3RjYV9zc2xfcnNhMjAyMi5jcmwwNaAzoDGGL2h0dHA6Ly9jb21wYW55LnJ0LnJ1
L2NkcC9yb290Y2Ffc3NsX3JzYTIwMjIuY3JsMDWgM6Axhi9odHRwOi8vcmVlc3Ry
LXBraS5ydS9jZHAvcm9vdGNhX3NzbF9yc2EyMDIyLmNybDANBgkqhkiG9w0BAQsF
AAOCAgEARBVzZls79AdiSCpar15dA5Hr/rrT4WbrOfzlpI+xrLeRPrUG6eUWIW4v
Sui1yx3iqGLCjPcKb+HOTwoRMbI6ytP/ndp3TlYua2advYBEhSvjs+4vDZNwXr/D
anbwIWdurZmViQRBDFebpkvnIvru/RpWud/5r624Wp8voZMRtj/cm6aI9LtvBfT9
cfzhOaexI/99c14dyiuk1+6QhdwKaCRTc1mdfNQmnfWNRbfWhWBlK3h4GGE9JK33
Gk8ZS8DMrkdAh0xby4xAQ/mSWAfWrBmfzlOqGyoB1U47WTOeqNbWkkoAP2ys94+s
Jg4NTkiDVtXRF6nr6fYi0bSOvOFg0IQrMXO2Y8gyg9ARdPJwKtvWX8VPADCYMiWH
h4n8bZokIrImVKLDQKHY4jCsND2HHdJfnrdL2YJw1qFskNO4cSNmZydw0Wkgjv9k
F+KxqrDKlB8MZu2Hclph6v/CZ0fQ9YuE8/lsHZ0Qc2HyiSMnvjgK5fDc3TD4fa8F
E8gMNurM+kV8PT8LNIM+4Zs+LKEV8nqRWBaxkIVJGekkVKO8xDBOG/aN62AZKHOe
GcyIdu7yNMMRihGVZCYr8rYiJoKiOzDqOkPkLOPdhtVlgnhowzHDxMHND/E2WA5p
ZHuNM/m0TXt2wTTPL7JH2YC0gPz/BvvSzjksgzU5rLbRyUKQkgU=
-----END CERTIFICATE-----
    """.trimIndent()

    /** Trust manager validating against ONLY the bundled Минцифры CAs. */
    fun russianTrustManager(): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        val cf = CertificateFactory.getInstance("X.509")
        ks.setCertificateEntry(
            "russian-trusted-root",
            cf.generateCertificate(RUSSIAN_ROOT_PEM.byteInputStream()) as X509Certificate,
        )
        ks.setCertificateEntry(
            "russian-trusted-sub",
            cf.generateCertificate(RUSSIAN_SUB_PEM.byteInputStream()) as X509Certificate,
        )
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /** The platform default trust manager (system CAs). */
    fun systemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Is [host] one of the Sber domains allowed to use the bundled Минцифры
     * anchors? Matching is label-exact: `sber.ru` and `sberbank.ru` themselves
     * plus any subdomain (`*.sber.ru`, `*.sberbank.ru`), case-insensitive,
     * tolerant of a trailing root dot. Look-alikes are NOT matches
     * (`notsber.ru`, `sber.ru.attacker.com`, `sberbank.ru.evil.net`), and an
     * IP literal / null / blank host is never a Sber host.
     */
    fun isSberHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().trimEnd('.').lowercase()
        if (normalized.isEmpty() || isIpLiteral(normalized)) return false
        return SBER_APEX_DOMAINS.any { apex ->
            normalized == apex || normalized.endsWith(".$apex")
        }
    }

    /**
     * Purely textual IPv4/IPv6 test — deliberately NOT
     * `InetAddress.getByName(...)`: that would put a reverse-DNS lookup (or a
     * blocking resolution) inside the TLS handshake path, and the policy for an
     * unresolvable host is "non-Sber" anyway.
     */
    internal fun isIpLiteral(host: String): Boolean {
        val bare = host.removeSurrounding("[")
        if (bare.indexOf(':') >= 0) return true // IPv6 (also covers zone ids)
        var groups = 1
        for (ch in bare) {
            when {
                ch == '.' -> groups++
                ch in '0'..'9' -> Unit
                else -> return false
            }
        }
        return groups == 4 // dotted quad
    }

    /**
     * Peer host of an in-flight handshake, in order of trustworthiness:
     * 1. the SNI server name the CLIENT requested — authoritative, and the only
     *    reliable one on the socket path: measured on JDK 17 with OkHttp's shape
     *    (`sslSocketFactory.createSocket()` + `connect(InetSocketAddress)` +
     *    SNI in SSLParameters), `handshakeSession.peerHost` reports the NUMERIC
     *    address while SNI carries the real host name;
     * 2. `handshakeSession.peerHost`, but only when it is a DNS name;
     * 3. the [SSLEngine] creation-time peer host;
     * 4. null — which every caller treats as "host unknown ⇒ NON-Sber".
     */
    internal fun peerHostName(session: SSLSession?, enginePeerHost: String? = null): String? {
        // Every accessor here is inside runCatching: OEM JSSE implementations
        // (Conscrypt included) are entitled to throw IllegalStateException /
        // UnsupportedOperationException from the extended session getters, and
        // a throw from THIS helper would abort the handshake of every client
        // that installs the wrapper. Degrading to the next signal — and
        // ultimately to "host unknown ⇒ platform-only" — is always safer.
        if (session is ExtendedSSLSession) {
            val sni = runCatching {
                session.requestedServerNames
                    .orEmpty()
                    .lastOrNull { it.type == SNI_HOST_NAME_TYPE }
                    ?.let { name: SNIServerName ->
                        String(name.encoded, StandardCharsets.US_ASCII).trim()
                    }
            }.getOrNull()
            if (!sni.isNullOrBlank()) return sni
        }
        runCatching {
            session?.peerHost?.takeIf { it.isNotBlank() && !isIpLiteral(it) }
        }.getOrNull()?.let { return it }
        return enginePeerHost?.takeIf { it.isNotBlank() && !isIpLiteral(it) }
    }

    /** [SSLEngine] handshake session, or null when the platform will not expose it. */
    internal fun handshakeSessionOf(engine: SSLEngine?): SSLSession? =
        engine?.let { runCatching { it.handshakeSession }.getOrNull() }

    /** [SSLSocket] handshake session, or null for a plain socket / unavailable session. */
    internal fun handshakeSessionOf(socket: Socket?): SSLSession? =
        (socket as? SSLSocket)?.let { runCatching { it.handshakeSession }.getOrNull() }

    /**
     * UNscoped composite: system CAs first, bundled Минцифры CAs as the
     * fallback, for EVERY host. This is the pre-decision-#4 shape and is far
     * too permissive to install directly — it exists only as the Sber-host
     * branch inside [compositeTrustManager]. Call sites that installed this
     * globally are exactly the vulnerability the wrapper closes.
     */
    fun sberCompositeTrustManager(): X509TrustManager {
        val system = systemTrustManager()
        val russian = russianTrustManager()
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkClientTrusted(chain, authType)
                } catch (_: CertificateException) {
                    russian.checkClientTrusted(chain, authType)
                }
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (_: CertificateException) {
                    russian.checkServerTrusted(chain, authType)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + russian.acceptedIssuers
        }
    }

    /**
     * The trust manager every Sber-facing client installs (AppGraph's shared
     * OkHttp client, the gRPC SSLContext, [withSberTrust]).
     *
     * HOST-SCOPED (audit decision #4): the returned manager validates Sber
     * hosts against [sberCompositeTrustManager] (system first, Минцифры
     * fallback) and every other host strictly against the platform default
     * trust manager. Signature and semantics-as-a-drop-in are unchanged from
     * the pre-fix version, so existing wiring compiles and works untouched;
     * only the trust ANCHOR SET is now narrowed per peer host.
     */
    fun compositeTrustManager(): X509TrustManager = SberHostScopedTrustManager(
        sberAnchors = sberCompositeTrustManager(),
        platformAnchors = systemTrustManager(),
    )

    fun sslContext(): SSLContext =
        SSLContext.getInstance("TLS").apply {
            // The wrapper is an X509ExtendedTrustManager, so JSSE hands it the
            // live handshake (engine/socket) and the host scoping applies to
            // raw-socket consumers too (the gRPC channel builds on this
            // factory without being handed a trust manager).
            init(null, arrayOf(compositeTrustManager()), SecureRandom())
        }
}

/**
 * Host-scoped routing in front of two anchor sets (audit decision #4).
 *
 * Routing table for `checkServerTrusted`:
 *
 * | peer host                                    | anchors used        |
 * |----------------------------------------------|---------------------|
 * | `sber.ru` / `*.sber.ru` / `sberbank.ru` / `*.sberbank.ru` | [sberAnchors] (system first + Минцифры fallback) |
 * | any other DNS name                           | [platformAnchors] ONLY |
 * | unknown / unresolvable / IP literal / no handshake context | [platformAnchors] ONLY |
 *
 * Rejections propagate from the selected branch and NOTHING else is tried:
 * a Sber-host failure never falls back to platform-only "helpfully", and a
 * non-Sber host is never offered the Минцифры anchors even when the platform
 * store rejects the chain. The no-context overloads (and
 * [checkServerTrusted] with neither engine nor socket) have no peer host to
 * scope on, so by policy they take the platform-only branch — unknown host ⇒
 * non-Sber.
 */
class SberHostScopedTrustManager(
    private val sberAnchors: X509TrustManager,
    private val platformAnchors: X509TrustManager,
) : X509ExtendedTrustManager() {

    private fun anchorsFor(host: String?): X509TrustManager =
        if (SberTrust.isSberHost(host)) sberAnchors else platformAnchors

    // --- server certificates (the only direction this app actually uses) ----

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        // No handshake, hence no peer host: unknown ⇒ NON-Sber.
        platformAnchors.checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) {
        val host = SberTrust.peerHostName(
            SberTrust.handshakeSessionOf(engine),
            engine?.peerHost,
        )
        anchorsFor(host).checkServerTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) {
        val host = SberTrust.peerHostName(SberTrust.handshakeSessionOf(socket))
        anchorsFor(host).checkServerTrusted(chain, authType)
    }

    // --- client certificates: same scope, same policy ----------------------

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        platformAnchors.checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        engine: SSLEngine?,
    ) {
        val host = SberTrust.peerHostName(
            SberTrust.handshakeSessionOf(engine),
            engine?.peerHost,
        )
        anchorsFor(host).checkClientTrusted(chain, authType)
    }

    override fun checkClientTrusted(
        chain: Array<X509Certificate>,
        authType: String,
        socket: Socket?,
    ) {
        val host = SberTrust.peerHostName(SberTrust.handshakeSessionOf(socket))
        anchorsFor(host).checkClientTrusted(chain, authType)
    }

    /**
     * Union of both anchor sets, deduplicated. Both branches' roots have to be
     * listed: consumers (OkHttp's chain cleaner, gRPC) use this to order
     * chains BEFORE any peer host is known, and a missing issuer there breaks
     * path building for legitimately trusted hosts without ever weakening the
     * per-host decision made above.
     */
    override fun getAcceptedIssuers(): Array<X509Certificate> =
        (sberAnchors.acceptedIssuers + platformAnchors.acceptedIssuers).distinct().toTypedArray()
}

/** Harden any OkHttp client that talks to the Sber endpoints. */
fun OkHttpClient.withSberTrust(): OkHttpClient {
    val tm = SberTrust.compositeTrustManager()
    return newBuilder()
        .sslSocketFactory(SberTrust.sslContext().socketFactory, tm)
        .build()
}
