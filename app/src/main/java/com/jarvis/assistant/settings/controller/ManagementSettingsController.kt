package com.jarvis.assistant.settings.controller

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.manage.ManagementServerProvider
import com.jarvis.assistant.manage.PasswordHasher
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.KeystoreVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * «Управление» / MANAGEMENT detail screen controller (R13 §14).
 *
 * The screen persists the mode/port/idle timeout, shows the **live listener
 * status** (running/stopped, bound port, TLS SHA-256 fingerprint) and offers an
 * explicit **activate/deactivate** action plus the one-time password reveal.
 *
 * The mode/port radio is the persisted INTENT ([ApplyPolicies.SERVICE_RESTART]);
 * the action calls the process-scoped [ManagementServerProvider] so an explicit
 * change can apply immediately where the design allows, without waiting for the
 * service restart the banner still advises for a port change. The provider is
 * owned by the FGS and survives graph rebuilds — this screen never starts a
 * server itself.
 *
 * The status row is refreshed off the main thread: the first status read may
 * generate the RSA cert, and the toggle may run a bounded Netty shutdown.
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
    private lateinit var statusValue: TextView
    private lateinit var statusDetail: TextView
    private lateinit var toggleButton: Button
    private lateinit var passwordValue: TextView

    /** Owned scope (the seam has no `lifecycleScope`); cancelled in [onStop]. */
    private var scope = newScope()

    override fun bind(root: View) {
        context = root.context
        vault = KeystoreVault.get(context.applicationContext)

        modeGroup = root.findViewById(R.id.managementModeGroup)
        portInput = root.findViewById(R.id.managementPortInput)
        idleInput = root.findViewById(R.id.managementIdleInput)
        statusValue = root.findViewById(R.id.managementStatusValue)
        statusDetail = root.findViewById(R.id.managementStatusDetail)
        toggleButton = root.findViewById(R.id.managementToggleButton)
        passwordValue = root.findViewById(R.id.managementPasswordValue)

        syncFromPrefs()
        bindMode()
        bindPort(root)
        bindIdle(root)
        bindPassword(root)
        bindToggle()

        refreshStatus()
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle".
        if (!::modeGroup.isInitialized) return
        // A stop→resume round-trip cancels the scope; revive it and redo the
        // (cancelled) status load.
        if (!scope.isActive) {
            scope = newScope()
        }
        syncFromPrefs()
        refreshStatus()
    }

    override fun onStop() {
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Mode / port / idle (persisted intent).
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Live status + activate/deactivate.
    // ------------------------------------------------------------------

    private fun bindToggle() {
        toggleButton.setOnClickListener {
            scope.launch {
                withContext(Dispatchers.Default) {
                    val provider = ManagementServerProvider.get(context.applicationContext)
                    if (provider.isRunning) provider.deactivate() else provider.activate()
                }
                refreshStatus()
            }
        }
    }

    /** Refresh the status row + toggle label from the process-scoped provider. */
    private fun refreshStatus() {
        scope.launch {
            val snapshot = withContext(Dispatchers.Default) {
                val provider = ManagementServerProvider.get(context.applicationContext)
                RuntimeSnapshot(
                    running = provider.isRunning,
                    port = provider.boundPort,
                    fingerprint = provider.fingerprint(),
                )
            }
            render(snapshot)
        }
    }

    private fun render(snapshot: RuntimeSnapshot) {
        statusValue.setText(
            if (snapshot.running) {
                R.string.settings_management_status_running
            } else {
                R.string.settings_management_status_stopped
            },
        )
        val fingerprint = snapshot.fingerprint.ifBlank {
            context.getString(R.string.settings_management_cert_failed)
        }
        statusDetail.text = context.getString(
            R.string.settings_management_status_detail,
            snapshot.port ?: prefs.managementPort,
            fingerprint,
        )
        toggleButton.setText(
            if (snapshot.running) {
                R.string.settings_management_deactivate
            } else {
                R.string.settings_management_activate
            },
        )
    }

    /**
     * "Показать пароль": return the stored password, generating and storing a
     * fresh one ONLY when none exists. Stored REVERSIBLY (the owner must be able
     * to re-show it), through the same [PasswordHasher] alphabet as the rest of
     * the lane. The vault write touches Keystore, so it runs off the main thread
     * inside [scope].
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
        val generated = PasswordHasher.generatePassword()
        vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, generated)
        return generated
    }

    // ------------------------------------------------------------------
    // Rendering helpers.
    // ------------------------------------------------------------------

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

    /** One status snapshot read off the main thread. */
    private data class RuntimeSnapshot(val running: Boolean, val port: Int?, val fingerprint: String)

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
    }
}
