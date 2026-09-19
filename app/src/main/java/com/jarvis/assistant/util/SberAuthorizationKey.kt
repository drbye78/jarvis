package com.jarvis.assistant.util

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Sber's developer console issues ONE credential per provider: an
 * "authorization key" that is `base64("<client_id>:<client_secret>")`. The
 * Settings screen, however, asks for a **client id** and a **client secret**
 * as two separate fields, and [CredentialsStore] stores exactly what it is
 * given. A user who pastes the combined key into the "секрет" field therefore
 * sends `Credentials.basic(id = <blank/other>, secret = <combined>)` and gets
 * an HTTP 400 from the OAuth probe — the exact failure this helper removes by
 * accepting either form.
 *
 * The split rules are deliberately tight so a genuine secret is never silently
 * reinterpreted: the decoded text must contain exactly one `:`, and the left
 * half must be a 36-char UUID-shaped client id. That last rule is what makes a
 * false positive on an ordinary Base64-looking secret effectively impossible.
 *
 * Pure JVM on purpose (no Android, no logging, no I/O): the decisions are
 * testable without Robolectric and credential material is never logged.
 */
object SberAuthorizationKey {

    /** Sber client ids are UUIDs; 36 chars of hex/dashes is the tight gate. */
    private val CLIENT_ID = Regex("[0-9a-fA-F-]{36}")

    /**
     * Splits a combined Sber authorization key (base64 of
     * `"client_id:client_secret"`) into its halves, or returns `null` when
     * [value] is not one.
     */
    fun split(value: String): Pair<String, String>? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        val text = decodeBase64(trimmed)?.let(::decodeUtf8) ?: return null
        val separator = text.indexOf(':')
        if (separator < 0) return null
        val id = text.substring(0, separator)
        val secret = text.substring(separator + 1)
        val malformed = text.indexOf(':', separator + 1) >= 0 ||
            secret.isEmpty() ||
            !CLIENT_ID.matches(id)
        return if (malformed) null else id to secret
    }

    /**
     * The effective `(id, secret)`: uses a combined key found in [secret] or
     * [id]; otherwise trims both.
     */
    fun normalize(id: String, secret: String): Pair<String, String> =
        split(secret) ?: split(id) ?: (id.trim() to secret.trim())

    /** Standard Base64 first, then the URL-safe alphabet; `null` for neither. */
    private fun decodeBase64(value: String): ByteArray? {
        val standard = try {
            Base64.getDecoder().decode(value)
        } catch (ignored: IllegalArgumentException) {
            null
        }
        if (standard != null) return standard
        return try {
            Base64.getUrlDecoder().decode(value)
        } catch (ignored: IllegalArgumentException) {
            null
        }
    }

    /** Strict UTF-8: malformed byte sequences are rejected, not replaced. */
    private fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (ignored: CharacterCodingException) {
        null
    }
}
