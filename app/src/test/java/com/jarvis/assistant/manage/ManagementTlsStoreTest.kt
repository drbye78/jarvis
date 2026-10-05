package com.jarvis.assistant.manage

import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R13 §14.2 persistence: the TLS cert — and therefore the SHA-256 fingerprint
 * the owner verifies — must survive a process restart. Each `ManagementTlsStore`
 * over the same vault stands in for one process, so constructing a second store
 * is the restart simulation.
 *
 * Also pins the corruption contract: absent, partial (one key), or undecodable
 * material regenerates and overwrites rather than crashing.
 */
class ManagementTlsStoreTest {

    private val p12Key = SecretVault.KEY_MANAGEMENT_TLS_P12
    private val passwordKey = SecretVault.KEY_MANAGEMENT_TLS_PASSWORD

    @Test
    fun `fingerprint survives a fresh store over the same vault`() {
        val vault = InMemoryVault()

        val first = ManagementTlsStore(vault).loadOrCreate()
        // A second store is a new process reading the same vault.
        val second = ManagementTlsStore(vault).loadOrCreate()

        assertEquals("the persisted cert must be byte-identical", first.certificate, second.certificate)
        assertEquals(
            "the verified fingerprint must not change on restart",
            first.sha256Fingerprint,
            second.sha256Fingerprint,
        )
        assertNotNull("the blob is persisted", vault.getString(p12Key))
        assertNotNull("the password is persisted", vault.getString(passwordKey))
    }

    @Test
    fun `absent material generates and persists the pair`() {
        val vault = InMemoryVault()

        val material = ManagementTlsStore(vault).loadOrCreate()

        assertTrue(material.sha256Fingerprint.isNotBlank())
        assertNotNull(vault.getString(p12Key))
        assertNotNull(vault.getString(passwordKey))
    }

    @Test
    fun `corrupt blob regenerates and the new material is then stable`() {
        val vault = InMemoryVault()
        vault.putString(p12Key, "!!!! not a keystore !!!!")
        vault.putString(passwordKey, "whatever")

        val recovered = ManagementTlsStore(vault).loadOrCreate()
        val reloaded = ManagementTlsStore(vault).loadOrCreate()

        assertEquals(
            "the regenerated cert must have been persisted",
            recovered.sha256Fingerprint,
            reloaded.sha256Fingerprint,
        )
    }

    @Test
    fun `wrong stored password regenerates and overwrites`() {
        val vault = InMemoryVault()
        val original = ManagementTlsStore(vault).loadOrCreate()
        vault.putString(passwordKey, "wrong-password")

        val regenerated = ManagementTlsStore(vault).loadOrCreate()

        assertNotEquals(
            "an undecodable blob must fall back to a fresh cert",
            original.sha256Fingerprint,
            regenerated.sha256Fingerprint,
        )
        assertEquals(
            "the overwritten password must unlock the regenerated cert",
            regenerated.sha256Fingerprint,
            ManagementTlsStore(vault).loadOrCreate().sha256Fingerprint,
        )
    }

    @Test
    fun `a partial write is treated as corrupt and regenerated`() {
        val p12Only = InMemoryVault().apply { putString(p12Key, "leftover") }
        ManagementTlsStore(p12Only).loadOrCreate()
        assertNotNull("the missing password half must be written", p12Only.getString(passwordKey))

        val passwordOnly = InMemoryVault().apply { putString(passwordKey, "leftover") }
        ManagementTlsStore(passwordOnly).loadOrCreate()
        assertNotNull("the missing blob half must be written", passwordOnly.getString(p12Key))
    }

    @Test
    fun `clear drops both keys`() {
        val vault = InMemoryVault()
        val store = ManagementTlsStore(vault)
        store.loadOrCreate()

        store.clear()

        assertNull(vault.getString(p12Key))
        assertNull(vault.getString(passwordKey))
    }
}
