package com.jarvis.assistant.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
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
 * Self-healing: an entry that fails to decrypt (key invalidated by a backup
 * restore, OEM keystore corruption) is removed and read as null instead of
 * throwing — the caller re-prompts for the secret, exactly the trade-off the
 * old SecurePrefs applied but without the crash-loop window. The master key
 * itself is created once; if the Keystore is so broken that key generation
 * fails, the exception PROPAGATES (fail-fast beats silently running with
 * plaintext-on-disk).
 *
 * The same plaintext encrypts to a different ciphertext every time (random
 * 12-byte IV per write), so identical keys/tokens are not linkable in the
 * prefs file.
 */
class KeystoreVault private constructor(private val prefs: SharedPreferences) : SecretVault {

    /** Lazily loaded/created master key; Keystore calls are cheap after first use. */
    private val masterKey: SecretKey by lazy { getOrCreateMasterKey() }

    override fun getString(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        if (stored.isBlank()) return null
        return try {
            decode(stored)
        } catch (e: Exception) {
            // AEADBadTagException / IllegalArgumentException / KeyStoreException —
            // the entry is undecryptable for THIS device's keystore. Drop it:
            // the caller sees null and re-prompts, no crash loop.
            Timber.w(e, "SecretVault: undecryptable entry for '%s' — dropping (re-enter required)", key)
            prefs.edit().remove(key).apply()
            null
        }
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
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }

    private fun decode(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        if (bytes.size <= IV_SIZE_BYTES) {
            throw IllegalArgumentException("Vault entry too short to contain IV || ciphertext")
        }
        val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, masterKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun getOrCreateMasterKey(): SecretKey {
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
         * the lazily loaded key.
         */
        @Synchronized
        fun get(context: Context): KeystoreVault =
            instances.getOrPut(PREFS_FILE) {
                val appContext = context.applicationContext
                KeystoreVault(
                    appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE),
                )
            }
    }
}
