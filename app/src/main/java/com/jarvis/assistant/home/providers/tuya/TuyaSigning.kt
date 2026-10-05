package com.jarvis.assistant.home.providers.tuya

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Tuya Cloud OpenAPI request signing (HMAC-SHA256).
 *
 * The recipe (docs "Sign Requests for Cloud Authorization"):
 * ```
 * bodyHash      = SHA256_hex(requestBody)          // empty body -> hash of ""
 * stringToSign  = method + "\n" + bodyHash + "\n" + signatureKey + "\n" + url
 * candidate     = clientId + accessToken + t + nonce + stringToSign
 *                  ^ accessToken is OMITTED for a token request
 * sign          = UPPER(HMAC_SHA256(candidate, secret))
 * ```
 * `url` is the request path plus its query string, with query parameters sorted
 * A→Z. Callers pass the already-canonical `url` (see [canonicalUrl]).
 *
 * Fully pure and deterministic: time (`t`) and `nonce` are injected by the
 * caller, so every step is JVM-testable and reproducible.
 */
object TuyaSigning {

    /** SHA-256 of the empty string; Tuya requires this exact constant for a bodyless request. */
    const val EMPTY_BODY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /** The only sign method Tuya accepts today. */
    const val SIGN_METHOD = "HMAC-SHA256"

    /**
     * The `stringToSign` for one request. [signatureKey] is the empty string for
     * all current requests (Tuya's optional signature-key feature is unused).
     */
    fun stringToSign(
        method: String,
        url: String,
        body: String?,
        signatureKey: String = "",
    ): String = buildString {
        append(method.uppercase())
        append('\n')
        append(contentSha256(body))
        append('\n')
        append(signatureKey)
        append('\n')
        append(url)
    }

    /**
     * The final signature: uppercase HMAC-SHA256 hex over
     * `clientId + accessToken + t + nonce + stringToSign`.
     *
     * [accessToken] is EMPTY for a token request and the current access token for
     * every business request — passing the wrong one is the single most common
     * cause of a generic signature error.
     */
    fun sign(
        clientId: String,
        secret: String,
        t: Long,
        nonce: String,
        accessToken: String,
        stringToSign: String,
    ): String = hmacSha256Hex(secret, clientId + accessToken + t + nonce + stringToSign).uppercase()

    /** SHA-256 hex of [body]; a null/empty body hashes the empty byte string. */
    fun contentSha256(body: String?): String {
        if (body.isNullOrEmpty()) return EMPTY_BODY_SHA256
        return MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
            .toHex()
    }

    /**
     * Canonical `url` = path + sorted `k=v` query (A→Z by key). Values are
     * URL-encoded exactly as they are sent. Returns just the path when there is
     * no query.
     */
    fun canonicalUrl(path: String, query: List<Pair<String, String>> = emptyList()): String {
        if (query.isEmpty()) return path
        val canonical = query
            .sortedBy { it.first }
            .joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$path?$canonical"
    }

    /** Uppercase HMAC-SHA256 hex of [data] under [key]. */
    fun hmacSha256Hex(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** RFC 3986 unreserved-only encoding, matching a query value Tuya expects. */
    private fun encode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt().toChar()
            if (isUnreserved(c)) {
                append(c)
            } else {
                append('%').append("%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    /** RFC 3986 unreserved: ALPHA / DIGIT / `-` / `_` / `.` / `~`. */
    private fun isUnreserved(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '-' || c == '_' || c == '.' || c == '~'
}
