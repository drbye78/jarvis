package com.jarvis.assistant.weather

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Optional HTTP/SOCKS proxy for the Open-Meteo API (forecast + geocoding).
 *
 * Deliberately Android-free so it stays unit-testable on the JVM and so the
 * proxy concern never leaks into the shared OkHttp client: [FunctionRouter]
 * derives a WEATHER-ONLY `OkHttpClient` from the graph's client and attaches
 * [LiveProxySelector] + [proxyAuthenticator] to it, leaving the LLM/TTS/gRPC
 * traffic untouched.
 *
 * The pref is read through [LiveProxySelector]'s `fetch` lambda on every
 * `select`, so a Settings change applies to the next weather request (LIVE, no
 * service restart).
 */
enum class ProxyScheme { HTTP, SOCKS }

/** A parsed proxy endpoint; [scheme] picks HTTP vs SOCKS at connect time. */
data class ProxySpec(
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
    val scheme: ProxyScheme = ProxyScheme.HTTP,
)

/**
 * Parse `host:port`, `http://host:port`, `https://host:port` or
 * `socks5://host:port`, each with an optional `user:pass@` userinfo prefix.
 *
 * Returns null for blank input, an unknown scheme, a missing/blank host or a
 * port outside 1..65535. `https://` is treated as a plain HTTP CONNECT proxy
 * (OkHttp speaks the proxy protocol, not the origin scheme).
 */
fun parse(raw: String): ProxySpec? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    val (schemeName, authority) = splitScheme(text)
    val scheme = schemeFor(schemeName) ?: return null
    return parseAuthority(authority, scheme)
}

/** `socks5` maps to [ProxyScheme.SOCKS]; `http`/`https`/absent map to HTTP. */
private fun schemeFor(name: String?): ProxyScheme? = when (name) {
    null, "http", "https" -> ProxyScheme.HTTP
    "socks5" -> ProxyScheme.SOCKS
    else -> null
}

/** Split an optional `scheme://` prefix off; absent prefix yields a null scheme. */
private fun splitScheme(text: String): Pair<String?, String> {
    val idx = text.indexOf("://")
    if (idx <= 0) return null to text
    return text.substring(0, idx).lowercase() to text.substring(idx + 3)
}

private fun parseAuthority(authority: String, scheme: ProxyScheme): ProxySpec? {
    if (authority.isBlank()) return null
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) authority.substring(0, at) else ""
    val hostPort = if (at >= 0) authority.substring(at + 1) else authority
    val colon = hostPort.lastIndexOf(':')
    if (colon <= 0 || colon == hostPort.length - 1) return null
    val host = hostPort.substring(0, colon).trim().removeSurrounding("[", "]")
    val port = hostPort.substring(colon + 1).trim().toIntOrNull() ?: return null
    if (host.isBlank() || port !in 1..65535) return null
    val (username, password) = splitUserInfo(userInfo)
    return ProxySpec(host = host, port = port, username = username, password = password, scheme = scheme)
}

/** `user:pass@` and `user@` are both accepted; empty userinfo yields nulls. */
private fun splitUserInfo(userInfo: String): Pair<String?, String?> = when {
    userInfo.isEmpty() -> null to null
    ':' in userInfo -> userInfo.substringBefore(':') to userInfo.substringAfter(':')
    else -> userInfo to ""
}

/** The `java.net.Proxy` for [spec] — SOCKS for `socks5://`, HTTP otherwise. */
fun javaProxy(spec: ProxySpec): Proxy {
    val type = if (spec.scheme == ProxyScheme.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP
    return Proxy(type, InetSocketAddress.createUnresolved(spec.host, spec.port))
}

/**
 * A [ProxySelector] backed by a LIVE [fetch] of the raw pref value.
 *
 * Every `select` re-reads the pref, so the Settings screen needs no restart.
 * Blank or unparseable values fail OPEN to [Proxy.NO_PROXY] (direct) instead
 * of breaking weather entirely.
 */
class LiveProxySelector(private val fetch: () -> String) : ProxySelector() {

    override fun select(uri: URI): List<Proxy> {
        val spec = parse(fetch())
        return if (spec == null) listOf(Proxy.NO_PROXY) else listOf(javaProxy(spec))
    }

    override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
}

/**
 * OkHttp [Authenticator] for a proxy that demands userinfo.
 *
 * It responds ONLY to a proxy challenge (407, or a `Proxy-Authenticate`
 * header) and only when the live pref carries a username + password; an
 * origin-server 401 returns null so this authenticator — attached to the
 * weather-only client — can never interfere with origin auth. A request that
 * already carries `Proxy-Authorization` returns null to avoid an auth retry
 * loop on bad credentials.
 */
fun proxyAuthenticator(fetch: () -> String): Authenticator = object : Authenticator {
    override fun authenticate(route: Route?, response: Response): Request? {
        if (!isProxyChallenge(response)) return null
        if (response.request.header("Proxy-Authorization") != null) return null
        val spec = parse(fetch())
        val user = spec?.username
        val password = spec?.password
        if (user == null || password == null) return null
        return response.request.newBuilder()
            .header("Proxy-Authorization", Credentials.basic(user, password))
            .build()
    }
}

private fun isProxyChallenge(response: Response): Boolean =
    response.code == 407 || response.header("Proxy-Authenticate") != null
