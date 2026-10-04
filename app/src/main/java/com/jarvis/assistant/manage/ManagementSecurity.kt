package com.jarvis.assistant.manage

import java.util.Locale

/**
 * The pure request-hardening rules for the R13 §14 management server.
 *
 * Everything here is a side-effect-free function of its inputs so the rules can
 * be pinned by a JVM test without standing up an HTTPS listener. The server
 * wires the results into Ktor responses (a 400 for a bad `Host`, a 403 for a
 * cross-origin unsafe request, and the response headers on every reply).
 *
 * Three defences, mirroring Go's `CrossOriginProtection` and §14.3:
 *
 *  - **`Host` allow-list** — the real DNS-rebinding defence. A browser that has
 *    been rebinding-attacked still sends the attacker's `Host`; only a host the
 *    listener is actually reachable as is accepted. The port is ignored (the
 *    allow-list names hosts, not endpoints) and IPv6 is handled in either the
 *    bracketed (`[::1]:8765`) or bare (`::1`) form.
 *  - **Cross-origin protection** — safe methods are always allowed; an unsafe
 *    method is allowed when `Sec-Fetch-Site` is `same-origin` **or `none`**
 *    (`none` is a user-initiated navigation, NOT a rejection), otherwise the
 *    `Origin` must name exactly the request `Host`. A missing `Origin` on an
 *    unsafe request is a rejection. There are NO CORS headers anywhere.
 *  - **Security headers** — a fixed base set on every response plus a strict CSP
 *    (no inline, no CDN) on HTML documents.
 */
object ManagementSecurity {

    const val HEADER_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options"
    const val HEADER_FRAME_OPTIONS = "X-Frame-Options"
    const val HEADER_REFERRER_POLICY = "Referrer-Policy"
    const val HEADER_CONTENT_SECURITY_POLICY = "Content-Security-Policy"
    const val HEADER_SEC_FETCH_SITE = "Sec-Fetch-Site"
    const val HEADER_ORIGIN = "Origin"
    const val HEADER_HOST = "Host"

    /** §14.3: no inline scripts/styles, no CDN, no framing, self-only connects. */
    const val CONTENT_SECURITY_POLICY =
        "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'"

    private const val SAME_ORIGIN = "same-origin"
    private const val NONE = "none"
    private const val HTTPS_DEFAULT_PORT = 443
    private const val HTTP_DEFAULT_PORT = 80

    /** Characters that make an authority malformed for our purposes. */
    private const val FORBIDDEN_AUTHORITY_CHARS = "@?#"

    /**
     * True when [hostHeader]'s host (port ignored) is in [allowedHosts].
     * A null/blank/malformed `Host` is NOT allowed — a request without a
     * trustworthy Host cannot be rebound-verified.
     */
    fun isAllowedHost(hostHeader: String?, allowedHosts: Set<String>): Boolean {
        val host = hostOnly(hostHeader) ?: return false
        return allowedHosts.any { hostOnly(it) == host }
    }

    /**
     * The Go `CrossOriginProtection` decision. Safe methods always pass; an
     * unsafe method passes on `Sec-Fetch-Site: same-origin`/`none`, else only
     * when the `Origin` authority equals the `Host` authority.
     */
    fun isCrossOriginAllowed(
        method: String,
        secFetchSite: String?,
        origin: String?,
        host: String?,
    ): Boolean {
        if (isSafeMethod(method)) return true
        val site = secFetchSite?.trim()?.lowercase(Locale.US)
        if (site == SAME_ORIGIN || site == NONE) return true
        val originAuthority = origin?.let(::originAuthority) ?: return false
        val hostAuthority = host?.let { hostAuthority(it, HTTPS_DEFAULT_PORT) } ?: return false
        return originAuthority == hostAuthority
    }

    /**
     * The fixed response headers. The HTML flag adds the CSP, which only makes
     * sense for a document the browser will execute.
     */
    fun securityHeaders(html: Boolean): Map<String, String> = buildMap {
        put(HEADER_CONTENT_TYPE_OPTIONS, "nosniff")
        put(HEADER_FRAME_OPTIONS, "DENY")
        put(HEADER_REFERRER_POLICY, "no-referrer")
        if (html) put(HEADER_CONTENT_SECURITY_POLICY, CONTENT_SECURITY_POLICY)
    }

    private fun isSafeMethod(method: String): Boolean = when (method.trim().uppercase(Locale.US)) {
        "GET", "HEAD", "OPTIONS" -> true
        else -> false
    }

    /** The host portion of a `Host`/`Origin` authority, lowercased, or null. */
    private fun hostOnly(value: String?): String? {
        val raw = value?.trim()?.lowercase(Locale.US) ?: return null
        if (raw.isEmpty()) return null
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            return if (end > 1) raw.substring(1, end) else null
        }
        val colons = raw.count { it == ':' }
        return when (colons) {
            0 -> raw
            1 -> raw.substringBefore(':')
            // A bare IPv6 literal is its own host.
            else -> raw
        }
    }

    /** `host:port` for a `Host` header value, port defaulted when absent. */
    private fun hostAuthority(hostHeader: String, defaultPort: Int): String? {
        val host = hostOnly(hostHeader) ?: return null
        return "$host:${portOf(hostHeader, defaultPort)}"
    }

    /** `host:port` for an absolute `Origin` URL, or null when not parseable. */
    private fun originAuthority(origin: String): String? {
        val trimmed = origin.trim()
        val separator = trimmed.indexOf("://")
        if (separator <= 0) return null
        val scheme = trimmed.substring(0, separator).lowercase(Locale.US)
        val authority = trimmed.substring(separator + 3).substringBefore('/')
        if (authority.isEmpty() || authority.any { it in FORBIDDEN_AUTHORITY_CHARS }) return null
        val defaultPort = if (scheme == "http") HTTP_DEFAULT_PORT else HTTPS_DEFAULT_PORT
        return hostAuthority(authority, defaultPort)
    }

    /** The explicit port of an authority, or [defaultPort] when it has none. */
    private fun portOf(authority: String, defaultPort: Int): Int {
        val raw = authority.trim().lowercase(Locale.US)
        if (raw.startsWith("[")) {
            val end = raw.indexOf(']')
            if (end < 0) return defaultPort
            return raw.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
        }
        if (raw.count { it == ':' } != 1) return defaultPort
        return raw.substringAfter(':').toIntOrNull() ?: defaultPort
    }
}
