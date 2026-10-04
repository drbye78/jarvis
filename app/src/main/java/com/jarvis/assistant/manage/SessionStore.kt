package com.jarvis.assistant.manage

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * In-memory browser session store for the management surface.
 *
 * Security invariant: **the raw session id is never stored** — only
 * `SHA-256(id)` (Base64url) is kept, alongside its expiry and last-seen time.
 * A heap dump of this process therefore does not yield a usable cookie. The id
 * itself is 256 bits from [SecureRandom], returned to the caller exactly once.
 *
 * The clock is injected as `() -> Long` epoch-millis so expiry and idle
 * behaviour are deterministic under test. All operations are synchronized
 * because the HTTP transport is multi-threaded.
 *
 * [verify] refreshes the idle timestamp on success; it must be called ONLY for
 * an authenticated request (the idle-close policy depends on that — §14.1).
 */
class SessionStore(
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private data class Session(val expiresAtMs: Long, val lastSeenAtMs: Long)

    private val random = SecureRandom()

    /** key = Base64url(SHA-256(id)) → session metadata; never the raw id. */
    private val sessions = HashMap<String, Session>()

    /**
     * Issue a fresh 256-bit session id valid for [ttlMs]. Returns the raw id
     * (the caller sets it as a cookie); it is not retained here.
     */
    @Synchronized
    fun issue(ttlMs: Long): String {
        val idBytes = ByteArray(ID_BYTES).also(random::nextBytes)
        val id = base64Url(idBytes)
        val now = nowMs()
        sessions[hashId(id)] = Session(expiresAtMs = now + ttlMs, lastSeenAtMs = now)
        return id
    }

    /**
     * True when [id] names a live session. An absolutely expired session is
     * pruned and rejected; a live one has its idle timestamp refreshed.
     */
    @Synchronized
    fun verify(id: String?): Boolean {
        val key = keyFor(id) ?: return false
        val session = sessions[key] ?: return false
        val now = nowMs()
        if (now >= session.expiresAtMs) {
            sessions.remove(key)
            return false
        }
        sessions[key] = session.copy(lastSeenAtMs = now)
        return true
    }

    /** Remove a single session; true if it existed. */
    @Synchronized
    fun revoke(id: String?): Boolean {
        val key = keyFor(id) ?: return false
        return sessions.remove(key) != null
    }

    /** Revoke every session (password change / mode change). */
    @Synchronized
    fun revokeAll() {
        sessions.clear()
    }

    /**
     * Drop sessions untouched for at least [idleMs]; returns the number
     * removed. A non-positive window is a no-op.
     */
    @Synchronized
    fun expireIdle(idleMs: Long): Int {
        if (idleMs <= 0) return 0
        val cutoff = nowMs() - idleMs
        val before = sessions.size
        sessions.entries.removeAll { (_, session) -> session.lastSeenAtMs <= cutoff }
        return before - sessions.size
    }

    /** Number of live (or not-yet-pruned) sessions; for `/status` and tests. */
    @Synchronized
    fun activeCount(): Int = sessions.size

    /** Base64url(SHA-256(id)) key, or null for a blank/null id. */
    private fun keyFor(id: String?): String? {
        val raw = id?.trim()
        if (raw.isNullOrEmpty()) return null
        return hashId(raw)
    }

    private fun hashId(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private companion object {
        const val ID_BYTES = 32
    }
}
