package com.jarvis.assistant

import com.jarvis.assistant.util.CredentialsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit #16/#30: the credential predicates are honest and the store works as
 * a plain class over SharedPreferences (the singleton wiring itself needs the
 * Android app lifecycle and is exercised by the app, not the JVM gate).
 */
class CredentialsStoreTest {

    private fun store(vararg pairs: Pair<String, String>): CredentialsStore {
        val prefs = FakePrefs()
        pairs.forEach { (k, v) -> prefs.edit().putString(k, v).commit() }
        return CredentialsStore(prefs)
    }

    @Test
    fun `mandatory keys are salute plus gigachat - picovoice is NOT required`() {
        val mandatory = store(
            "salute_client_id" to "sid",
            "salute_client_secret" to "ssecret",
            "gigachat_client_id" to "gid",
            "gigachat_client_secret" to "gsecret",
        )
        assertTrue(mandatory.hasMandatoryApiKeys())
        // The old hasRequiredSber() ALSO demanded the Picovoice key — false
        // "required" for the default offline Sherpa engine.
        assertFalse(mandatory.hasPicovoiceKey())
    }

    @Test
    fun `picovoice key alone does not make the mandatory set`() {
        val sherpaOnly = store("picovoice_key" to "pv")
        assertFalse(sherpaOnly.hasMandatoryApiKeys())
        assertTrue(sherpaOnly.hasPicovoiceKey())
    }

    @Test
    fun `empty store has nothing`() {
        val empty = CredentialsStore(FakePrefs())
        assertFalse(empty.hasMandatoryApiKeys())
        assertFalse(empty.hasPicovoiceKey())
        assertTrue(empty.saluteClientId.isEmpty())
    }

    @Test
    fun `setters trim and persist through the prefs seam`() {
        val prefs = FakePrefs()
        val s = CredentialsStore(prefs)
        s.saluteClientId = "  sid  "
        assertTrue(prefs.map["salute_client_id"] == "sid")
        assertTrue(s.saluteClientId == "sid")
    }
}
