package com.jarvis.assistant.settings.controller

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.manage.TlsCertFactory
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.KeystoreVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.security.SecureRandom

/**
 * «Управление» / MANAGEMENT detail screen controller (R13 §14, Wave 1).
 *
 * SETTINGS + DISPLAY ONLY: this screen never starts the management server — a
 * later wave owns the listener. It persists three prefs and shows two
 * device-bound values:
 *
 *  - `managementMode` (disabled | localhost | lan) and `managementPort` are
 *    [ApplyPolicies.SERVICE_RESTART]: the listener binds a specific
 *    interface:port at service start, so a change is stored and the pending
 *    banner reports the restart need.
 *  - `managementIdleTimeoutMs` is LIVE: the running server re-reads it per
 *    request. Stored in ms, shown/edited in whole minutes.
 *  - the self-signed certificate SHA-256 fingerprint is computed OFF the main
 *    thread via [TlsCertFactory.generate] and shown so the expected browser
 *    warning can be verified. Cheap (one generation per screen bind) and
 *    cancelled with [scope].
 *  - the management password is generated on first reveal (20 chars,
 *    unambiguous alphabet) and stored in the [SecretVault]; it is shown only
 *    on request, never by default.
 *
 * The frozen seam has no `lifecycleScope`, so the controller owns a
 * [CoroutineScope], cancels it in [onStop] and revives it in [onResume].
 */
class ManagementSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var context: Context

    /**
     * The vault is read/written DIRECTLY (not through `CredentialsStore`): the
     * management password has no typed, zero-arg accessor — it is a one-off
     * device value reached through its fixed [SecretVault] key.
     */
    private lateinit var vault: SecretVault

    private lateinit var modeGroup: RadioGroup
    private lateinit var portInput: TextInputEditText
    private lateinit var idleInput: TextInputEditText
    private lateinit var certFingerprint: TextView
    private lateinit var passwordValue: TextView

    /** Owned scope (the seam has no `lifecycleScope`); cancelled in [onStop]. */
    private var scope = newScope()

    /** Guards against re-generating the RSA cert on every resume. */
    private var fingerprintLoading = false

    override fun bind(root: View) {
        context = root.context
        vault = KeystoreVault.get(context.applicationContext)

        modeGroup = root.findViewById(R.id.managementModeGroup)
        portInput = root.findViewById(R.id.managementPortInput)
        idleInput = root.findViewById(R.id.managementIdleInput)
        certFingerprint = root.findViewById(R.id.managementCertFingerprint)
        passwordValue = root.findViewById(R.id.managementPasswordValue)

        syncFromPrefs()
        bindMode()
        bindPort(root)
        bindIdle(root)
        bindPassword(root)

        loadFingerprint()
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle".
        if (!::modeGroup.isInitialized) return
        // A stop→resume round-trip cancels the scope; revive it and redo the
        // (cancelled) fingerprint load.
        if (!scope.isActive) {
            scope = newScope()
            fingerprintLoading = false
            loadFingerprint()
        }
        syncFromPrefs()
    }

    override fun onStop() {
        scope.cancel()
    }

    /**
     * Mode radio. The STORED value is applied before the listener is attached,
     * so the programmatic `check()` can never be mistaken for a user edit.
     * A change is stored and marked pending (SERVICE_RESTART).
     */
    private fun bindMode() {
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = modeFor(checkedId)
            if (mode == prefs.managementMode) return@setOnCheckedChangeListener
            prefs.managementMode = mode
            host.markPending(ApplyPolicies.of(PREF_MODE))
        }
    }

    /**
     * Port field: committed on the explicit Save button and, like the free-text
     * fields elsewhere, on IME-done / focus loss. An out-of-range value is
     * rejected with an honest toast and the field re-seeded from the pref; a
     * successful change is marked pending (SERVICE_RESTART).
     */
    private fun bindPort(root: View) {
        portInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitPort()
                true
            } else {
                false
            }
        }
        portInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitPort() }
        root.findViewById<Button>(R.id.managementPortSaveButton).setOnClickListener {
            commitPort()
            host.toast(R.string.settings_management_saved)
        }
    }

    private fun commitPort() {
        val parsed = portInput.text.toString().trim().toIntOrNull()
        if (parsed == null || parsed !in MIN_PORT..MAX_PORT) {
            host.toast(R.string.settings_management_port_invalid)
            syncPort()
            return
        }
        if (parsed == prefs.managementPort) return
        prefs.managementPort = parsed
        host.markPending(ApplyPolicies.of(PREF_PORT))
    }

    /**
     * Idle-timeout field (minutes ↔ `managementIdleTimeoutMs`). LIVE, so a valid
     * change applies to the running server without a restart hint.
     */
    private fun bindIdle(root: View) {
        idleInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitIdle()
                true
            } else {
                false
            }
        }
        idleInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitIdle() }
        root.findViewById<Button>(R.id.managementIdleSaveButton).setOnClickListener {
            commitIdle()
            host.toast(R.string.settings_management_saved)
        }
    }

    private fun commitIdle() {
        val minutes = idleInput.text.toString().trim().toLongOrNull()
        if (minutes == null || minutes !in MIN_IDLE_MINUTES..MAX_IDLE_MINUTES) {
            host.toast(R.string.settings_management_idle_invalid)
            syncIdle()
            return
        }
        prefs.managementIdleTimeoutMs = minutes * MILLIS_PER_MINUTE
    }

    /**
     * "Показать пароль": return the stored password, generating and storing a
     * fresh one ONLY when none exists. The vault write touches Keystore, so it
     * runs off the main thread inside [scope].
     */
    private fun bindPassword(root: View) {
        root.findViewById<Button>(R.id.managementShowPasswordButton).setOnClickListener {
            scope.launch {
                val password = withContext(Dispatchers.Default) { ensurePassword() }
                passwordValue.text = password
            }
        }
    }

    private fun ensurePassword(): String {
        val stored = vault.getString(SecretVault.KEY_MANAGEMENT_PASSWORD)
        if (!stored.isNullOrBlank()) return stored
        val generated = generatePassword()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, generated)
        return generated
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        return buildString(PASSWORD_LENGTH) {
            repeat(PASSWORD_LENGTH) {
                append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)])
            }
        }
    }

    /** Compute the cert fingerprint once per bind, off the main thread. */
    private fun loadFingerprint() {
        if (fingerprintLoading) return
        fingerprintLoading = true
        certFingerprint.text = context.getString(R.string.settings_management_cert_loading)
        scope.launch {
            val fingerprint = withContext(Dispatchers.Default) { buildFingerprint() }
            certFingerprint.text = fingerprint
                ?: context.getString(R.string.settings_management_cert_failed)
        }
    }

    private fun buildFingerprint(): String? = try {
        TlsCertFactory.generate().sha256Fingerprint
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Timber.w(error, "Management: TLS certificate generation failed")
        null
    }

    /** Render every control from the prefs (single source of truth). */
    private fun syncFromPrefs() {
        modeGroup.check(radioFor(prefs.managementMode))
        syncPort()
        syncIdle()
    }

    private fun syncPort() {
        portInput.setText(prefs.managementPort.toString())
    }

    private fun syncIdle() {
        idleInput.setText((prefs.managementIdleTimeoutMs / MILLIS_PER_MINUTE).toString())
    }

    /** Radio id → the stored mode string. */
    private fun modeFor(checkedId: Int): String = when (checkedId) {
        R.id.managementModeLocalhost -> MODE_LOCALHOST
        R.id.managementModeLan -> MODE_LAN
        else -> MODE_DISABLED
    }

    /** Stored mode string → the radio that represents it; unknown ⇒ disabled. */
    private fun radioFor(mode: String): Int = when (mode) {
        MODE_LOCALHOST -> R.id.managementModeLocalhost
        MODE_LAN -> R.id.managementModeLan
        else -> R.id.managementModeDisabled
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /** Persisted mode values; also the property-name form for [ApplyPolicies]. */
        const val MODE_DISABLED = "disabled"
        const val MODE_LOCALHOST = "localhost"
        const val MODE_LAN = "lan"

        /** Property names; [ApplyPolicies] normalizes storage keys identically. */
        const val PREF_MODE = "managementMode"
        const val PREF_PORT = "managementPort"

        const val MIN_PORT = 1
        const val MAX_PORT = 65_535
        const val MIN_IDLE_MINUTES = 1L
        const val MAX_IDLE_MINUTES = 1_440L
        const val MILLIS_PER_MINUTE = 60_000L

        const val PASSWORD_LENGTH = 20

        /** Unambiguous alphabet: no O/0, no I/l. */
        const val PASSWORD_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
    }
}
