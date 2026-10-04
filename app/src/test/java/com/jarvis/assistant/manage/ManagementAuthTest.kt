package com.jarvis.assistant.manage

import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The password gate: dual-format verification (Argon2id record or legacy
 * plaintext), constant-time-ish comparison behavior, and per-IP rate limiting.
 */
class ManagementAuthTest {

    private val password = "correct horse battery staple".toCharArray()

    private fun newPreparedAuth(): Pair<ManagementAuth, InMemoryVault> {
        val vault = InMemoryVault()
        val auth = ManagementAuth(
            readStoredSecret = { vault.getString(SecretVault.KEY_MANAGEMENT_PASSWORD) },
            writeStoredSecret = { vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, it) },
            maxFailures = 3,
            lockoutMs = 60_000L,
        )
        return auth to vault
    }

    @Test
    fun `verifies a stored argon2id record`() {
        val (auth, vault) = newPreparedAuth()
        vault.putString(
            SecretVault.KEY_MANAGEMENT_PASSWORD,
            PasswordHasher.encodeRecord(PasswordHasher.hash(password, Argon2Params.LOW_MEMORY)),
        )

        assertEquals(ManagementAuthResult.Ok, auth.authenticate("127.0.0.1", password.copyOf()))
        assertEquals(
            ManagementAuthResult.BadPassword,
            auth.authenticate("127.0.0.1", "wrong".toCharArray()),
        )
    }

    @Test
    fun `verifies a legacy plaintext secret`() {
        val (auth, vault) = newPreparedAuth()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, "plain-text-password")

        assertEquals(ManagementAuthResult.Ok, auth.authenticate("10.0.0.1", "plain-text-password".toCharArray()))
        assertEquals(ManagementAuthResult.BadPassword, auth.authenticate("10.0.0.1", password.copyOf()))
    }

    @Test
    fun `an absent or blank secret can never authenticate`() {
        val (auth, _) = newPreparedAuth()

        assertEquals(ManagementAuthResult.BadPassword, auth.authenticate("127.0.0.1", "".toCharArray()))
        assertEquals(ManagementAuthResult.BadPassword, auth.authenticate("127.0.0.1", password.copyOf()))
    }

    @Test
    fun `lockout engages after the failure limit and reports retry-after`() {
        val (auth, vault) = newPreparedAuth()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, "the-password")

        assertEquals(ManagementAuthResult.BadPassword, auth.authenticate("1.2.3.4", "nope".toCharArray()))
        assertEquals(ManagementAuthResult.BadPassword, auth.authenticate("1.2.3.4", "nope".toCharArray()))
        val third = auth.authenticate("1.2.3.4", "nope".toCharArray())
        assertTrue("third failure locks the source", third is ManagementAuthResult.LockedOut)
        assertTrue((third as ManagementAuthResult.LockedOut).retryAfterSeconds >= 1L)
        assertTrue(auth.isLocked("1.2.3.4"))
        assertTrue(auth.retryAfterSeconds("1.2.3.4") >= 1L)

        val stillLocked = auth.authenticate("1.2.3.4", "the-password".toCharArray())
        assertTrue("a correct password is still rejected while locked", stillLocked is ManagementAuthResult.LockedOut)
        assertEquals(
            "other sources are unaffected",
            ManagementAuthResult.Ok,
            auth.authenticate("5.6.7.8", "the-password".toCharArray()),
        )
    }

    @Test
    fun `a success clears the failure history`() {
        val (auth, vault) = newPreparedAuth()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, "the-password")

        auth.authenticate("9.9.9.9", "nope".toCharArray())
        auth.authenticate("9.9.9.9", "nope".toCharArray())
        assertEquals(ManagementAuthResult.Ok, auth.authenticate("9.9.9.9", "the-password".toCharArray()))
        assertEquals(0L, auth.retryAfterSeconds("9.9.9.9"))
        assertFalse(auth.isLocked("9.9.9.9"))
    }

    @Test
    fun `change password writes a hashed record the old password no longer matches`() {
        val (auth, vault) = newPreparedAuth()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, "the-password")
        val next = "brand-new-password"

        assertEquals(
            ManagementAuthResult.Ok,
            auth.changePassword("1.1.1.1", "the-password".toCharArray(), next.toCharArray()),
        )
        val stored = vault.getString(SecretVault.KEY_MANAGEMENT_PASSWORD)
        assertNotNull("the stored form is a decodable PasswordRecord", PasswordHasher.decodeRecord(stored))
        assertEquals(ManagementAuthResult.Ok, auth.authenticate("1.1.1.1", next.toCharArray()))
        assertEquals(ManagementAuthResult.BadPassword, auth.authenticate("1.1.1.1", "the-password".toCharArray()))
    }

    @Test
    fun `change password rejects a wrong current password without writing`() {
        val (auth, vault) = newPreparedAuth()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, "the-password")

        assertEquals(
            ManagementAuthResult.BadPassword,
            auth.changePassword("1.1.1.1", "wrong".toCharArray(), "next".toCharArray()),
        )
        assertEquals("the-password", vault.getString(SecretVault.KEY_MANAGEMENT_PASSWORD))
    }
}
