package com.jarvis.assistant.manage

import com.jarvis.assistant.util.SecretVault
import timber.log.Timber

/**
 * Durable owner of the R13 §14.2 management server's TLS material.
 *
 * WHY PERSIST: a freshly generated cert carries a fresh SHA-256 fingerprint, so
 * a cert generated per process (or per listener bind) invalidates every browser
 * exception the owner has stored the moment the service restarts. The PKCS#12
 * keystore (Base64) and the password that unlocks it are persisted in the
 * [SecretVault] under two fixed keys; [loadOrCreate] returns the SAME
 * certificate — and thus the same fingerprint — across process restarts.
 *
 * DEGRADATION: continuity is a convenience, never a startup dependency. A
 * missing, partial (only one key present), or corrupt/wrong-password blob is
 * treated as absent: a fresh cert is generated and the persisted pair is
 * overwritten. [loadOrCreate] never throws for vault content.
 *
 * This is the ONLY place TLS material is created; callers cache the returned
 * material for the lifetime of a process but must not generate a cert
 * themselves.
 *
 * SECRECY: the Blob carries the private key and the password unlocks it, so
 * neither is ever logged. Only the (non-secret) fact that a fresh cert was
 * generated is surfaced, at WARN.
 */
class ManagementTlsStore(private val vault: SecretVault) {

    /**
     * The persisted material when both keys are present and decode cleanly,
     * otherwise a freshly generated one — which is then persisted for the next
     * process. Never throws.
     */
    fun loadOrCreate(): TlsMaterial = loadPersisted() ?: generateAndPersist()

    /** Drops both persisted keys; the next [loadOrCreate] generates a new cert. */
    fun clear() {
        vault.remove(SecretVault.KEY_MANAGEMENT_TLS_P12)
        vault.remove(SecretVault.KEY_MANAGEMENT_TLS_PASSWORD)
    }

    /**
     * Both keys must be present: a p12 without its password (or vice versa) is
     * a partial write and is treated as corrupt, never half-used. A blob that
     * fails to decode (corrupt/truncated/wrong password/missing alias) is
     * likewise ignored so [loadOrCreate] regenerates.
     */
    private fun loadPersisted(): TlsMaterial? {
        val encoded = vault.getString(SecretVault.KEY_MANAGEMENT_TLS_P12) ?: return null
        val password = vault.getString(SecretVault.KEY_MANAGEMENT_TLS_PASSWORD) ?: return null
        if (encoded.isBlank() || password.isBlank()) return null
        val passwordChars = password.toCharArray()
        return try {
            TlsCertFactory.decode(encoded, passwordChars)
        } finally {
            // decode keeps its own copy of the password, so the transient array
            // is safe to wipe here.
            passwordChars.fill('\u0000')
        }
    }

    private fun generateAndPersist(): TlsMaterial {
        val material = TlsCertFactory.generate()
        Timber.w("ManagementTlsStore: no usable persisted TLS material — generated a fresh cert")
        persist(material)
        return material
    }

    /**
     * Best-effort persistence: the listener keeps the in-process material even
     * if the vault write fails, so a broken vault never blocks startup — the
     * next process simply regenerates.
     *
     * The password is written BEFORE the blob. A crash between the two writes
     * then leaves a password without a p12, which [loadPersisted] treats as
     * corrupt and regenerates; the reverse order would strand a blob whose
     * password is unknown. Each individual vault write is atomic.
     */
    private fun persist(material: TlsMaterial) {
        try {
            val encoded = TlsCertFactory.encode(material)
            vault.putString(SecretVault.KEY_MANAGEMENT_TLS_PASSWORD, String(material.keyPassword))
            vault.putString(SecretVault.KEY_MANAGEMENT_TLS_P12, encoded)
        } catch (e: Exception) {
            Timber.w(e, "ManagementTlsStore: could not persist the generated TLS material")
        }
    }
}
