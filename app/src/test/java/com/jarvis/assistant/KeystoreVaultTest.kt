package com.jarvis.assistant

import com.jarvis.assistant.util.KeystoreVault
import com.jarvis.assistant.util.OneShotHealGate
import com.jarvis.assistant.util.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * COGNITIVE_PLAN 0.4: the vault's self-heal is NARROW and BUDGETED.
 * - key-material failures (GeneralSecurityException / IOException /
 *   truncated or malformed entries) heal: entry dropped, read returns null;
 * - the destructive heal happens at most once per process (the second
 *   undecryptable entry returns null WITHOUT deleting — a systemic keystore
 *   failure must stop destroying data);
 * - anything else (a bug: RuntimeException from the key provider) FAILS
 *   FAST instead of masquerading as corruption;
 * - roundtrip + IV randomness on a software AES key (JVM-safe).
 */
class KeystoreVaultTest {

    private fun softwareKey(): SecretKey =
        SecretKeySpec(ByteArray(32) { (it * 7 + 1).toByte() }, "AES")

    private fun vault(
        prefs: FakeSharedPreferences = FakeSharedPreferences(),
        gate: OneShotHealGate = OneShotHealGate(),
        keyProvider: () -> SecretKey = ::softwareKey,
    ): Pair<KeystoreVault, FakeSharedPreferences> =
        KeystoreVault(prefs, masterKeyProvider = keyProvider, healGate = gate) to prefs

    @Test
    fun `roundtrip encrypts and decrypts`() {
        val (vault, prefs) = vault()
        vault.putString(SecretVault.KEY_OPENAI_API_KEY, "sk-test-123")
        assertEquals("sk-test-123", vault.getString(SecretVault.KEY_OPENAI_API_KEY))

        // IV randomness: the same plaintext encrypts to a different
        // ciphertext each write (non-linkable at rest).
        vault.putString(SecretVault.KEY_OPENAI_API_KEY, "sk-test-123")
        val storedTwice = prefs.map[SecretVault.KEY_OPENAI_API_KEY] as String
        vault.putString(SecretVault.KEY_OPENAI_API_KEY, "sk-test-123")
        val storedThrice = prefs.map[SecretVault.KEY_OPENAI_API_KEY] as String
        assertTrue(storedTwice != storedThrice)
    }

    @Test
    fun `truncated entry heals once - dropped and reads null`() {
        val (vault, prefs) = vault()
        prefs.map[SecretVault.KEY_PICOVOICE] = "abc" // too short to hold IV||ciphertext
        assertNull(vault.getString(SecretVault.KEY_PICOVOICE))
        assertFalse("the corrupt entry must be gone after the heal", prefs.map.containsKey(SecretVault.KEY_PICOVOICE))
    }

    @Test
    fun `second corrupt entry in the same process is NOT deleted (budget exhausted)`() {
        val usedGate = OneShotHealGate()
        assertTrue(usedGate.tryAcquire()) // simulate the budget already spent
        val (vault, prefs) = vault(gate = usedGate)
        prefs.map[SecretVault.KEY_SALUTE_ID] = "not-base64!!"
        assertNull(vault.getString(SecretVault.KEY_SALUTE_ID))
        assertTrue(
            "after the heal budget is spent the vault must NOT destroy more data",
            prefs.map.containsKey(SecretVault.KEY_SALUTE_ID),
        )
    }

    @Test
    fun `tampered ciphertext heals within budget`() {
        val (vault, prefs) = vault()
        // A realistic tampered payload: valid 12-byte IV + garbage ciphertext
        // and tag (the GCM tag check must fail).
        prefs.map[SecretVault.KEY_GIGA_ID] =
            "AAAAAAAAAAAAAAAAAAAAAbr7smWlSishCwSCi2SHRWN/1mKmZPo="
        assertNull(vault.getString(SecretVault.KEY_GIGA_ID))
        assertFalse(prefs.map.containsKey(SecretVault.KEY_GIGA_ID))
    }

    /** A NON-crypto failure: a bug in the key provider, not corruption. */
    private class BrokenKeyProvider : RuntimeException("keystore exploded")

    @Test
    fun `non-crypto failure from the key provider propagates (fail-fast)`() {
        val (vault, _) = vault(keyProvider = { throw BrokenKeyProvider() })
        try {
            vault.putString(SecretVault.KEY_OPENAI_API_KEY, "x")
            fail("a broken key provider is a BUG — it must propagate, not self-heal")
        } catch (expected: BrokenKeyProvider) {
            assertEquals("keystore exploded", expected.message)
        }
    }

    @Test
    fun `one-shot gate fires exactly once`() {
        val gate = OneShotHealGate()
        assertTrue(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
        assertFalse(gate.tryAcquire())
    }
}
