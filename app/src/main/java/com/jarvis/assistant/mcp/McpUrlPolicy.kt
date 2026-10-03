package com.jarvis.assistant.mcp

import java.net.URI

/**
 * Why a candidate MCP URL was rejected.
 *
 * The values are deliberately coarse and safe to log — they never echo the
 * host back (a private address can itself be sensitive).
 */
enum class UrlRejection {
    MALFORMED,
    UNSUPPORTED_SCHEME,
    HTTPS_REQUIRED,
    MISSING_HOST,
    LOOPBACK_HOST,
    PRIVATE_HOST,
    LINK_LOCAL_HOST,
    METADATA_HOST,
    NON_LOOPBACK_HOST,
    CROSS_ORIGIN_PRIVATE_HOST,
}

/** Outcome of [McpUrlPolicy.validate]. */
sealed interface UrlPolicyResult {
    data object Allowed : UrlPolicyResult

    data class Rejected(val reason: UrlRejection) : UrlPolicyResult
}

/**
 * How a hostname/IP literal is classified. [PUBLIC] also covers a non-literal
 * hostname we cannot resolve in a pure check.
 */
enum class HostClass { LOOPBACK, PRIVATE, LINK_LOCAL, METADATA, MALFORMED, PUBLIC }

/**
 * Pure SSRF / localhost policy for MCP server endpoints.
 *
 * This is the security boundary for the multi-server MCP lane and is
 * deliberately I/O-free — it NEVER resolves DNS. Consequences of that choice,
 * spelled out because they are security-relevant:
 *
 * - A literal IP is classified exactly (loopback, RFC-1918 private, link-local,
 *   the cloud-metadata address, IPv6 ULA/link-local, and IPv4-mapped IPv6).
 * - A **hostname other than `localhost` is treated as public**. A name such as
 *   `internal.corp` or `10.0.0.5.nip.io` that actually resolves to a private
 *   address is therefore allowed here; rebinding/private-resolution defence
 *   belongs at CONNECT time (a later lane), not in a pure validator.
 * - `REMOTE` requires `https`; a private/loopback literal is rejected.
 * - `LOCAL` requires a loopback host and is the only place cleartext `http` is
 *   permitted.
 */
object McpUrlPolicy {

    private val IPV4_SHAPE = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    /** Validate a configured server [url] under [kind]'s policy. */
    fun validate(kind: McpServerKind, url: String): UrlPolicyResult {
        val uri = parse(url)
        val scheme = uri?.scheme
        val host = uri?.host?.removeSurrounding("[", "]")
        val rejection = when {
            uri == null -> UrlRejection.MALFORMED
            scheme == null -> UrlRejection.UNSUPPORTED_SCHEME
            else -> checkScheme(kind, scheme)
        }
        if (rejection != null) return UrlPolicyResult.Rejected(rejection)
        if (host.isNullOrEmpty()) return UrlPolicyResult.Rejected(UrlRejection.MISSING_HOST)
        return hostResult(kind, classifyHost(host))
    }

    /**
     * Validate a redirect target. Beyond re-applying [validate] to the target,
     * a `REMOTE` redirect that changes host to a private/loopback literal is
     * rejected as [UrlRejection.CROSS_ORIGIN_PRIVATE_HOST] — the classic
     * "public URL 302s to the metadata service" SSRF. Host comparison only; no
     * DNS.
     */
    fun validateRedirect(kind: McpServerKind, originUrl: String, targetUrl: String): UrlPolicyResult {
        if (kind == McpServerKind.REMOTE) {
            val originHost = hostOf(originUrl)
            val targetHost = hostOf(targetUrl)
            val crossesToNonPublic = originHost != null && targetHost != null &&
                !originHost.equals(targetHost, ignoreCase = true) &&
                classifyHost(targetHost) != HostClass.PUBLIC
            if (crossesToNonPublic) {
                return UrlPolicyResult.Rejected(UrlRejection.CROSS_ORIGIN_PRIVATE_HOST)
            }
        }
        return validate(kind, targetUrl)
    }

    /** Classify a host from a URL without any I/O. */
    fun classifyHost(host: String): HostClass {
        val bare = host.trim().removeSurrounding("[", "]")
        if (bare.isEmpty()) return HostClass.MALFORMED
        if (bare.equals("localhost", ignoreCase = true)) return HostClass.LOOPBACK
        val literal = parseLiteral(bare)
        if (literal != null) return classifyBytes(literal)
        return if (looksLikeIpAttempt(bare)) HostClass.MALFORMED else HostClass.PUBLIC
    }

    /** `http`/`https` for LOCAL, `https` only for REMOTE. */
    private fun checkScheme(kind: McpServerKind, scheme: String): UrlRejection? {
        val normalized = scheme.lowercase()
        return when {
            kind == McpServerKind.REMOTE && normalized == "https" -> null
            kind == McpServerKind.REMOTE && normalized == "http" -> UrlRejection.HTTPS_REQUIRED
            kind == McpServerKind.REMOTE -> UrlRejection.UNSUPPORTED_SCHEME
            normalized == "http" || normalized == "https" -> null
            else -> UrlRejection.UNSUPPORTED_SCHEME
        }
    }

    private fun hostResult(kind: McpServerKind, hostClass: HostClass): UrlPolicyResult =
        when (kind) {
            McpServerKind.REMOTE -> when (hostClass) {
                HostClass.LOOPBACK -> UrlPolicyResult.Rejected(UrlRejection.LOOPBACK_HOST)
                HostClass.PRIVATE -> UrlPolicyResult.Rejected(UrlRejection.PRIVATE_HOST)
                HostClass.LINK_LOCAL -> UrlPolicyResult.Rejected(UrlRejection.LINK_LOCAL_HOST)
                HostClass.METADATA -> UrlPolicyResult.Rejected(UrlRejection.METADATA_HOST)
                HostClass.MALFORMED -> UrlPolicyResult.Rejected(UrlRejection.MALFORMED)
                HostClass.PUBLIC -> UrlPolicyResult.Allowed
            }

            McpServerKind.LOCAL -> when (hostClass) {
                HostClass.LOOPBACK -> UrlPolicyResult.Allowed
                HostClass.MALFORMED -> UrlPolicyResult.Rejected(UrlRejection.MALFORMED)
                else -> UrlPolicyResult.Rejected(UrlRejection.NON_LOOPBACK_HOST)
            }
        }

    private fun hostOf(url: String): String? =
        parse(url)?.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() }

    private fun parse(url: String): URI? {
        val text = url.trim()
        if (text.isEmpty()) return null
        return runCatching { URI(text) }.getOrNull()
    }

    /** A real IP literal or embedded IPv4 for a mapped IPv6 address. */
    private fun parseLiteral(host: String): ByteArray? =
        if (host.contains(':')) parseIpv6(host) else parseIpv4(host)

    private fun parseIpv4(host: String): ByteArray? {
        if (!IPV4_SHAPE.matches(host)) return null
        val parts = host.split('.')
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    private fun parseIpv6(host: String): ByteArray? =
        runCatching { ipv6Bytes(host) }.getOrNull()

    /**
     * Parse a textual IPv6 address to 16 bytes. Any malformed input aborts via
     * [IllegalArgumentException], funnelled to a null by [parseIpv6]. Embedded
     * IPv4 (`::ffff:1.2.3.4`) is intentionally not parsed — it fails closed to
     * a rejection rather than being misread as a public host.
     */
    private fun ipv6Bytes(host: String): ByteArray {
        val zone = host.indexOf('%')
        val text = if (zone >= 0) host.substring(0, zone) else host
        require(text.contains(':')) { "not an IPv6 literal" }
        val marker = text.indexOf("::")
        require(marker < 0 || text.indexOf("::", marker + 2) < 0) { "repeated ::" }
        val headText = if (marker >= 0) text.substring(0, marker) else text
        val tailText = if (marker >= 0) text.substring(marker + 2) else ""
        val head = if (headText.isEmpty()) emptyList() else headText.split(':')
        val tail = if (tailText.isEmpty()) emptyList() else tailText.split(':')
        require((head + tail).none { it.isEmpty() }) { "empty group" }
        require(head.size + tail.size <= 8) { "too many groups" }
        require(marker >= 0 || head.size == 8) { "uncompressed address needs 8 groups" }
        require(marker < 0 || head.size + tail.size <= 7) { ":: must compress at least one group" }
        val values = (head + tail).map(::hexGroup)
        val groups = ArrayList<Int>(8)
        if (marker < 0) {
            groups.addAll(values)
        } else {
            groups.addAll(values.subList(0, head.size))
            repeat(8 - values.size) { groups.add(0) }
            groups.addAll(values.subList(head.size, values.size))
        }
        require(groups.size == 8) { "not 8 groups" }
        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i * 2] = (groups[i] ushr 8).toByte()
            bytes[i * 2 + 1] = groups[i].toByte()
        }
        return bytes
    }

    private fun hexGroup(token: String): Int {
        require(token.length in 1..4) { "bad group length" }
        require(token.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) { "bad hex digit" }
        return token.toInt(16)
    }

    private fun classifyBytes(bytes: ByteArray): HostClass = when (bytes.size) {
        4 -> classifyIpv4(bytes)
        16 -> classifyIpv6(bytes)
        else -> HostClass.MALFORMED
    }

    private fun classifyIpv4(b: ByteArray): HostClass {
        val a = b[0].toInt() and 0xFF
        val c = b[1].toInt() and 0xFF
        val d = b[2].toInt() and 0xFF
        val e = b[3].toInt() and 0xFF
        return when {
            a == 0 -> HostClass.PRIVATE // 0.0.0.0/8 "this network" — never a valid remote
            a == 127 -> HostClass.LOOPBACK
            a == 10 -> HostClass.PRIVATE
            a == 172 && c in 16..31 -> HostClass.PRIVATE
            a == 192 && c == 168 -> HostClass.PRIVATE
            a == 169 && c == 254 && d == 169 && e == 254 -> HostClass.METADATA
            a == 169 && c == 254 -> HostClass.LINK_LOCAL
            else -> HostClass.PUBLIC
        }
    }

    private fun classifyIpv6(b: ByteArray): HostClass {
        val mapped = b.take(10).all { it == 0.toByte() } && b[10] == 0xFF.toByte() && b[11] == 0xFF.toByte()
        if (mapped) return classifyIpv4(b.copyOfRange(12, 16))
        val zeroPrefix = b.take(15).all { it == 0.toByte() }
        val first = b[0].toInt() and 0xFF
        val second = b[1].toInt() and 0xFF
        return when {
            zeroPrefix && b[15] == 1.toByte() -> HostClass.LOOPBACK
            zeroPrefix && b[15] == 0.toByte() -> HostClass.PRIVATE // unspecified `::`
            (first and 0xFE) == 0xFC -> HostClass.PRIVATE // fc00::/7 unique-local
            first == 0xFE && (second and 0xC0) == 0x80 -> HostClass.LINK_LOCAL // fe80::/10
            else -> HostClass.PUBLIC
        }
    }

    /** True for a string that can only be intended as an IP literal. */
    private fun looksLikeIpAttempt(host: String): Boolean =
        host.contains(':') || host.all { it.isDigit() || it == '.' }
}
