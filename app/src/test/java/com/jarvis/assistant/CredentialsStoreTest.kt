package com.jarvis.assistant

import com.jarvis.assistant.util.CredentialsStore
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit #16/#30: the credential predicates are honest and the store works as
 * a plain class over a [SecretVault] (the Keystore wiring itself needs the
 * Android app lifecycle and is exercised by the app, not the JVM gate).
 *
 * A3: the backing store is now the vault abstraction — the deprecated
 * EncryptedSharedPreferences is gone from the production path.
 */
class CredentialsStoreTest {

    private fun store(vararg pairs: Pair<String, String>): CredentialsStore {
        val vault = InMemoryVault()
        pairs.forEach { (k, v) -> vault.putString(k, v) }
        return CredentialsStore(vault)
    }

    @Test
    fun `mandatory keys are salute plus gigachat - picovoice is NOT required`() {
        val mandatory = store(
            SecretVault.KEY_SALUTE_ID to "sid",
            SecretVault.KEY_SALUTE_SECRET to "ssecret",
            SecretVault.KEY_GIGA_ID to "gid",
            SecretVault.KEY_GIGA_SECRET to "gsecret",
        )
        assertTrue(mandatory.hasMandatoryApiKeys())
        // The old hasRequiredSber() ALSO demanded the Picovoice key — false
        // "required" for the default offline Sherpa engine.
        assertFalse(mandatory.hasPicovoiceKey())
    }

    @Test
    fun `picovoice key alone does not make the mandatory set`() {
        val sherpaOnly = store(SecretVault.KEY_PICOVOICE to "pv")
        assertFalse(sherpaOnly.hasMandatoryApiKeys())
        assertTrue(sherpaOnly.hasPicovoiceKey())
    }

    @Test
    fun `empty store has nothing`() {
        val empty = CredentialsStore(InMemoryVault())
        assertFalse(empty.hasMandatoryApiKeys())
        assertFalse(empty.hasPicovoiceKey())
        assertTrue(empty.saluteClientId.isEmpty())
    }

    @Test
    fun `setters trim and persist through the vault seam`() {
        val vault = InMemoryVault()
        val s = CredentialsStore(vault)
        s.saluteClientId = "  sid  "
        assertEquals("sid", vault.getString(SecretVault.KEY_SALUTE_ID))
        assertEquals("sid", s.saluteClientId)
    }

    @Test
    fun `vault entries read back as null when missing`() {
        val vault = InMemoryVault()
        assertNull(vault.getString(SecretVault.KEY_GIGA_SECRET))
        vault.putString(SecretVault.KEY_GIGA_SECRET, "x")
        assertEquals("x", vault.getString(SecretVault.KEY_GIGA_SECRET))
        vault.remove(SecretVault.KEY_GIGA_SECRET)
        assertNull(vault.getString(SecretVault.KEY_GIGA_SECRET))
    }

    @Test
    fun `hasNonBlank distinguishes blank from missing`() {
        val vault = InMemoryVault()
        assertFalse(vault.hasNonBlank(SecretVault.KEY_PICOVOICE))
        vault.putString(SecretVault.KEY_PICOVOICE, "")
        assertFalse(vault.hasNonBlank(SecretVault.KEY_PICOVOICE))
        vault.putString(SecretVault.KEY_PICOVOICE, "k")
        assertTrue(vault.hasNonBlank(SecretVault.KEY_PICOVOICE))
    }
}
