package com.jarvis.assistant.manage

import java.security.MessageDigest

/**
 * The outcome of a password authentication attempt.
 *
 * [LockedOut] carries the `Retry-After` seconds the transport must report; the
 * lockout is per source IP and resets on success.
 */
sealed interface ManagementAuthResult {
    data object Ok : ManagementAuthResult

    /** Wrong password (or a blank stored secret, which can never match). */
    data object BadPassword : ManagementAuthResult

    data class LockedOut(val retryAfterSeconds: Long) : ManagementAuthResult
}

/**
 * The password + rate-limit gate for the R13 §14 management surface.
 *
 * **Password storage — the decision (documented at the call site too).** The
 * server verifies against whatever the vault's `management_password` slot holds
 * and accepts BOTH representations:
 *
 *  1. a [PasswordRecord] JSON (preferred) — decoded and verified with Argon2id
 *     via [PasswordHasher.verify]; a password change through this class writes
 *     this form, so the plaintext is never persisted by the server; and
 *  2. a **plaintext** string (legacy) — compared with
 *     [MessageDigest.isEqual] (constant-time for equal lengths).
 *
 * The settings controller (`ManagementSettingsController`) still generates and
 * writes the 20-char plaintext, so this compatibility branch is required until a
 * follow-up lane migrates that write to [PasswordHasher.encodeRecord]. Keeping
 * the read side tolerant means the server can ship before the UI lane changes.
 *
 * **Rate limiting.** Failed attempts are counted per source IP in a rolling
 * window; once [maxFailures] is reached the IP is locked for [lockoutMs] and a
 * lockout is returned even for a correct password. A success clears the counter.
 * The map is bounded and expired entries are pruned so a LAN client cannot grow
 * it without limit.
 */
class ManagementAuth(
    private val readStoredSecret: () -> String?,
    private val writeStoredSecret: (String) -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = DEFAULT_MAX_FAILURES,
    private val failureWindowMs: Long = DEFAULT_FAILURE_WINDOW_MS,
    private val lockoutMs: Long = DEFAULT_LOCKOUT_MS,
) {

    private class AttemptState(
        var failures: Int,
        var firstFailureAtMs: Long,
        var lockedUntilMs: Long,
    )

    private val attempts = LinkedHashMap<String, AttemptState>()

    /**
     * Verify [password] for [sourceIp]; on success clear that IP's failures, on
     * failure count it and possibly enter the lockout.
     */
    @Synchronized
    fun authenticate(sourceIp: String, password: CharArray): ManagementAuthResult {
        val now = nowMs()
        val state = attempts[sourceIp]
        if (state != null && now < state.lockedUntilMs) {
            return ManagementAuthResult.LockedOut(secondsCeil(state.lockedUntilMs - now))
        }
        if (verify(password)) {
            attempts.remove(sourceIp)
            return ManagementAuthResult.Ok
        }
        return registerFailure(sourceIp, now)
    }

    /** True while [sourceIp] is in its lockout window. */
    @Synchronized
    fun isLocked(sourceIp: String): Boolean {
        val state = attempts[sourceIp] ?: return false
        return nowMs() < state.lockedUntilMs
    }

    /** Seconds until [sourceIp]'s lockout expires; 0 when not locked. */
    @Synchronized
    fun retryAfterSeconds(sourceIp: String): Long {
        val state = attempts[sourceIp] ?: return 0L
        val remaining = state.lockedUntilMs - nowMs()
        return if (remaining <= 0L) 0L else secondsCeil(remaining)
    }

    /** Forget [sourceIp]'s failure history (used on a successful auth). */
    @Synchronized
    fun reset(sourceIp: String) {
        attempts.remove(sourceIp)
    }

    /**
     * Verify [current], then store [next] as a fresh Argon2id [PasswordRecord].
     * Returns the current-password failure/lockout result unchanged; an empty
     * [next] is reported as [ManagementAuthResult.BadPassword] (the route
     * rejects it as a 400 before reaching here, so this is only a safety net).
     */
    @Synchronized
    fun changePassword(sourceIp: String, current: CharArray, next: CharArray): ManagementAuthResult {
        val result = authenticate(sourceIp, current)
        if (result !is ManagementAuthResult.Ok) return result
        if (next.isEmpty() || next.concatToString().isBlank()) return ManagementAuthResult.BadPassword
        writeStoredSecret(PasswordHasher.encodeRecord(PasswordHasher.hash(next)))
        return ManagementAuthResult.Ok
    }

    private fun registerFailure(sourceIp: String, now: Long): ManagementAuthResult {
        prune(now)
        val state = attempts.getOrPut(sourceIp) {
            AttemptState(failures = 0, firstFailureAtMs = now, lockedUntilMs = 0L)
        }
        if (state.firstFailureAtMs <= 0L || now - state.firstFailureAtMs >= failureWindowMs) {
            state.failures = 0
            state.firstFailureAtMs = now
        }
        state.failures++
        if (state.failures >= maxFailures) {
            state.lockedUntilMs = now + lockoutMs
            state.failures = 0
            state.firstFailureAtMs = now
            return ManagementAuthResult.LockedOut(secondsCeil(lockoutMs))
        }
        return ManagementAuthResult.BadPassword
    }

    /**
     * The dual-format verification. A decodable [PasswordRecord] uses Argon2id;
     * anything else is treated as a plaintext secret and compared in constant
     * time. A blank/absent secret is compared against a fixed dummy so an empty
     * password can never authenticate.
     */
    private fun verify(password: CharArray): Boolean {
        val stored = readStoredSecret()
        PasswordHasher.decodeRecord(stored)?.let { record ->
            return PasswordHasher.verify(password, record)
        }
        val expected = (stored?.takeIf { it.isNotBlank() } ?: DUMMY_SECRET).toByteArray(Charsets.UTF_8)
        val actual = password.concatToString().toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(actual, expected)
    }

    private fun prune(now: Long) {
        attempts.entries.removeAll { (_, state) ->
            val lockExpired = state.lockedUntilMs in 1..now
            val windowExpired = state.lockedUntilMs == 0L && now - state.firstFailureAtMs >= failureWindowMs
            lockExpired || windowExpired
        }
        while (attempts.size > MAX_TRACKED_SOURCES) {
            val eldest = attempts.keys.firstOrNull() ?: break
            attempts.remove(eldest)
        }
    }

    private fun secondsCeil(millis: Long): Long = (millis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND

    companion object {
        const val DEFAULT_MAX_FAILURES = 10
        const val DEFAULT_FAILURE_WINDOW_MS = 5L * 60L * 1000L
        const val DEFAULT_LOCKOUT_MS = 5L * 60L * 1000L

        private const val MILLIS_PER_SECOND = 1000L
        private const val MAX_TRACKED_SOURCES = 256
        private const val DUMMY_SECRET = "\u0000jarvis-management-no-password"
    }
}
