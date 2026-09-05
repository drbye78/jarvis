package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.security.KeyStore
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Production [SecretVault]: AES-256-GCM under an AndroidKeyStore master key,
 * ciphertext (Base64 of IV || bytes) persisted in a plain SharedPreferences
 * file.
 *
 * Why not EncryptedSharedPreferences (the previous implementation)? The
 * Jetpack security-crypto library is deprecated upstream; this class keeps
 * the same threat model (device-bound key, at-rest confidentiality) with
 * zero library dependencies and explicit, auditable crypto.
 *
 * Self-healing, NARROWED (COGNITIVE_PLAN 0.4 — the re-audit's catch-all):
 * an entry that fails to decrypt because the KEY MATERIAL is wrong for this
 * device (key invalidated by a backup restore, OEM keystore corruption →
 * [GeneralSecurityException] family, or a truncated entry) is dropped and
 * read as null — the caller re-prompts for the secret, no crash loop.
 * Anything else (a bug, [RuntimeException], OOM) PROPAGATES: masking
 * programming errors as "corrupt entry" used to silently destroy vault
 * data.
 *
 * The heal is also BUDGETED once per process: the first undecryptable read
 * heals (drops its entry). Every further failure in the same process is
 * treated as a SYSTEMIC keystore problem — reads still return null (same
 * caller UX), but the vault stops deleting data. [java.util.Base64] and an
 * injectable master-key provider keep the whole policy JVM-unit-testable.
 *
 * The same plaintext encrypts to a different ciphertext every time (random
 * 12-byte IV per write), so identical keys/tokens are not linkable in the
 * prefs file.
 */
class KeystoreVault internal constructor(
    private val prefs: SharedPreferences,
    /** 0.4 test seam: overrides the AndroidKeyStore master key (JVM tests). */
    private val masterKeyProvider: () -> SecretKey,
    /** 0.4 test seam: the once-per-process destructive-heal budget gate. */
    private val healGate: OneShotHealGate,
) : SecretVault {

    /** Lazily resolved master key; Keystore calls are cheap after first use. */
    private val masterKey: SecretKey by lazy { masterKeyProvider() }

    override fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (stored.isBlank()) return null
        return try {
            decode(stored)
        } catch (e: java.io.IOException) {
            handleUndecryptable(key, e)
        } catch (e: GeneralSecurityException) {
            // AEADBadTagException (tampered/other-key ciphertext),
            // InvalidKeyException, KeyStoreException — the entry is
            // undecryptable for THIS device's keystore.
            handleUndecryptable(key, e)
        } catch (e: java.security.ProviderException) {
            // OEM keystore providers famously wrap AEAD/tag failures in this
            // RuntimeException subclass — it is key-material trouble, not a
            // caller bug, so it belongs in the (budgeted) heal path.
            handleUndecryptable(key, e)
        } catch (e: IllegalArgumentException) {
            // Our own "entry too short" guard and Base64 format errors.
            handleUndecryptable(key, e)
        }
    }

    /**
     * 0.4: heal only within the once-per-process budget. The first failure
     * drops the entry (caller re-prompts); later failures return null
     * WITHOUT deleting — a second corrupt entry in one process means the
     * keystore itself is broken, and destroying more data would not help.
     */
    private fun handleUndecryptable(key: String, cause: Exception): String? {
        if (healGate.tryAcquire()) {
            Timber.w(cause, "SecretVault: undecryptable entry for '%s' — dropping (re-enter required)", key)
            prefs.edit().remove(key).apply()
        } else {
            Timber.e(
                cause,
                "SecretVault: undecryptable entry for '%s' — self-heal budget used; " +
                    "NOT deleting (possible systemic keystore failure)",
                key,
            )
        }
        return null
    }

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, encode(value)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, masterKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val b64 = java.util.Base64.getEncoder().encodeToString(iv + encrypted)
        return b64
    }

    private fun decode(stored: String): String {
        val bytes = java.util.Base64.getDecoder().decode(stored)
        if (bytes.size <= IV_SIZE_BYTES) {
            throw IllegalArgumentException("Vault entry too short to contain IV || ciphertext")
        }
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "jarvis_vault_master_v2"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private const val PREFS_FILE = "jarvis_secure_v2"

        private val instances = HashMap<String, KeystoreVault>()

        /**
         * Per-process singleton keyed by prefs file name (the old SecurePrefs
         * contract, minus the deprecated crypto). Thread-safe: construction
         * happens under the class lock and the object is stateless besides
         * the lazily loaded key. The heal gate is shared by the singleton —
         * one instance per process makes instance-level == process-level.
         */
        @Synchronized
        fun get(context: Context): KeystoreVault =
            instances.getOrPut(PREFS_FILE) {
                val appContext = context.applicationContext
                KeystoreVault(
                    appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
                    masterKeyProvider = ::resolveAndroidMasterKey,
                    healGate = PROCESS_HEAL_GATE,
                )
            }

        /** The production once-per-process heal budget (shared by the singleton). */
        private val PROCESS_HEAL_GATE = OneShotHealGate()

        /**
         * Real AndroidKeyStore master-key resolution: reuse the existing key
         * or generate it once. Keystore failures here PROPAGATE (fail-fast —
         * silently running with plaintext-on-disk is worse than crashing).
         */
        private fun resolveAndroidMasterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (keyStore.getEntry(MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
                return it.secretKey
            }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    MASTER_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}

/**
 * 0.4: a process-scoped one-shot gate for the vault's DESTRUCTIVE self-heal.
 * `tryAcquire()` returns true exactly once per process instance; afterwards
 * the gate stays closed and the vault reads undecryptable entries as null
 * without deleting them. Injected in tests to exercise both paths.
 */
class OneShotHealGate {
    private val used = AtomicBoolean(false)

    /** True exactly once per gate lifetime; afterwards always false. */
    fun tryAcquire(): Boolean = used.compareAndSet(false, true)
}
