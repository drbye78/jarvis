package com.jarvis.assistant.settings.controller

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.llm.CredentialCheck
import com.jarvis.assistant.llm.CredentialCheckController
import com.jarvis.assistant.llm.OAuthCredentialValidator
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.ui.FieldValidation
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import com.jarvis.assistant.util.SberAuthorizationKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Аккаунты и ключи» / ACCOUNTS detail screen controller (settings redesign, F-E).
 *
 * Ports the old Activity's `setupCredentialsCard` / `attachCredentialWatchers` /
 * `textWatcher` / `renderCheckStatus` / `saveCredentials` /
 * `writeBackSplitCredential` behaviour onto `screen_settings_accounts.xml`.
 *
 * NO DISCLOSURE here: every row is an access key (frozen design decision), so
 * all provider keys are visible and grouped by provider.
 *
 * SPLIT-SCREEN CONTRACT with BRAIN: the [OI]-compatible API key lives HERE while
 * its base URL, model and the Yandex folder id live on BRAIN.
 * [SettingsCallbacks.onSaveLlmProviderSettings] persists ALL FOUR arguments, so
 * this screen can only ever write the key — it must reuse the STORED
 * `openAiBaseUrl` / `openAiModel` / `yandexFolderId` or it would silently erase
 * what the BRAIN screen owns.
 *
 * Credentials are LIVE (the host invalidates tokens + rebuilds the wake-word
 * engine), so the only restart-scoped value on this screen is the [OI] key,
 * which is marked pending when it actually changes.
 *
 * The frozen seam has no `lifecycleScope`, so the controller owns a
 * [CoroutineScope] for the credential probe and cancels it in [onStop];
 * [onResume] revives it (recreating [CredentialCheckController]) after a
 * stop→resume round-trip.
 */
class AccountsSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var context: Context

    private lateinit var picovoiceKey: TextInputEditText
    private lateinit var saluteId: TextInputEditText
    private lateinit var saluteSecret: TextInputEditText
    private lateinit var gigaChatId: TextInputEditText
    private lateinit var gigaChatSecret: TextInputEditText
    private lateinit var yandexApiKey: TextInputEditText
    private lateinit var openAiApiKey: TextInputEditText

    private lateinit var saluteCheckStatus: TextView
    private lateinit var gigaChatCheckStatus: TextView
    private lateinit var checkButton: Button
    private lateinit var saveButton: Button

    /** Owned scope (the seam has no `lifecycleScope`); cancelled in [onStop]. */
    private var scope = newScope()

    /** The live probe state machine; recreated when the scope is revived. */
    private var credentialChecks: CredentialCheckController? = null

    override fun bind(root: View) {
        context = root.context
        picovoiceKey = root.findViewById(R.id.picovoiceKey)
        saluteId = root.findViewById(R.id.saluteId)
        saluteSecret = root.findViewById(R.id.saluteSecret)
        gigaChatId = root.findViewById(R.id.gigaChatId)
        gigaChatSecret = root.findViewById(R.id.gigaChatSecret)
        yandexApiKey = root.findViewById(R.id.yandexApiKey)
        openAiApiKey = root.findViewById(R.id.openAiApiKey)
        saluteCheckStatus = root.findViewById(R.id.saluteCheckStatus)
        gigaChatCheckStatus = root.findViewById(R.id.gigaChatCheckStatus)
        checkButton = root.findViewById(R.id.checkCredentialsButton)
        saveButton = root.findViewById(R.id.saveCredentialsButton)

        // Pre-fill every field from its backing store. The [OI] key lives in
        // AppPrefs (SecretVault slot), unlike the CredentialsStore members.
        val store = CredentialsStore.get()
        picovoiceKey.setText(store.picovoiceKey)
        saluteId.setText(store.saluteClientId)
        saluteSecret.setText(store.saluteClientSecret)
        gigaChatId.setText(store.gigaChatClientId)
        gigaChatSecret.setText(store.gigaChatClientSecret)
        yandexApiKey.setText(store.yandexApiKey)
        openAiApiKey.setText(prefs.openAiApiKey)

        // Attach watchers AFTER the pre-fill so the population setText events
        // never reach the controller; bindCredentialChecks seeds the saved pair
        // explicitly below.
        attachCredentialWatchers()

        checkButton.setOnClickListener { credentialChecks?.checkNow() }
        saveButton.setOnClickListener {
            credentialChecks?.checkNow()
            saveCredentials()
        }

        bindCredentialChecks()
    }

    override fun onResume() {
        if (!::picovoiceKey.isInitialized) return
        // A stop→resume round-trip cancels the scope and with it the probe
        // collectors; rebuild them so the status rows and Watch button work.
        if (!scope.isActive) {
            scope = newScope()
            bindCredentialChecks()
        }
    }

    override fun onStop() {
        scope.cancel()
    }

    /**
     * (Re)create the credential state machine on the current scope, seed it
     * with the fields' current values and start rendering its states. Recreated
     * (not reused) after a scope revive, because its `init` collectors are bound
     * to the cancelled scope.
     */
    private fun bindCredentialChecks() {
        val checks = CredentialCheckController(
            validator = OAuthCredentialValidator(),
            scope = scope,
        )
        credentialChecks = checks

        // Only probe Sber while the Sber backend is ACTIVE. The pair stays shown
        // and saveable regardless of backend, but probing it fires a live OAuth
        // request at a provider the user is not using — the old card gated this
        // the same way, and an account screen should not generate egress the
        // user did not ask for. Re-evaluated on every bind/revive because the
        // backend radio lives on the Speech screen (leaving this screen cancels
        // the scope, so returning rebuilds this and re-reads the pref).
        checks.setSaluteValidationEnabled(prefs.speechBackend == SpeechBackend.SBER)

        val (saluteIdValue, saluteSecretValue) = SberAuthorizationKey.normalize(
            saluteId.text.toString(),
            saluteSecret.text.toString(),
        )
        checks.onSaluteInput(saluteIdValue, saluteSecretValue)
        val (gigaIdValue, gigaSecretValue) = SberAuthorizationKey.normalize(
            gigaChatId.text.toString(),
            gigaChatSecret.text.toString(),
        )
        checks.onGigaChatInput(gigaIdValue, gigaSecretValue)

        scope.launch {
            checks.states.collect { state ->
                renderCheckStatus(saluteCheckStatus, state[CredentialCheckController.Service.SALUTE])
                renderCheckStatus(gigaChatCheckStatus, state[CredentialCheckController.Service.GIGACHAT])
            }
        }
        // Opening the panel health-checks the SAVED pairs too.
        checks.checkNow()
    }

    /** Feed the credential controller on every keystroke in the four fields. */
    private fun attachCredentialWatchers() {
        val saluteWatcher = textWatcher {
            val (id, secret) = SberAuthorizationKey.normalize(
                saluteId.text.toString(),
                saluteSecret.text.toString(),
            )
            credentialChecks?.onSaluteInput(id, secret)
        }
        saluteId.addTextChangedListener(saluteWatcher)
        saluteSecret.addTextChangedListener(saluteWatcher)

        val gigaWatcher = textWatcher {
            val (id, secret) = SberAuthorizationKey.normalize(
                gigaChatId.text.toString(),
                gigaChatSecret.text.toString(),
            )
            credentialChecks?.onGigaChatInput(id, secret)
        }
        gigaChatId.addTextChangedListener(gigaWatcher)
        gigaChatSecret.addTextChangedListener(gigaWatcher)
    }

    private fun textWatcher(action: () -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = action()
    }

    /** Map one service's [CredentialCheckController.UiState] onto its status row. */
    private fun renderCheckStatus(view: TextView, state: CredentialCheckController.UiState?) {
        when (state) {
            null, CredentialCheckController.UiState.Idle -> view.visibility = View.GONE
            CredentialCheckController.UiState.Checking -> {
                view.visibility = View.VISIBLE
                view.text = context.getString(R.string.credentials_checking)
                view.setTextColor(ContextCompat.getColor(context, R.color.jarvis_on_surface_variant))
            }
            is CredentialCheckController.UiState.Verdict -> {
                view.visibility = View.VISIBLE
                when (val check = state.check) {
                    CredentialCheck.Valid -> {
                        view.text = context.getString(R.string.credentials_ok)
                        view.setTextColor(ContextCompat.getColor(context, R.color.jarvis_status_listening))
                    }
                    is CredentialCheck.Invalid -> {
                        view.text = check.httpCode?.let {
                            context.getString(R.string.credentials_invalid_http, it)
                        } ?: context.getString(R.string.credentials_invalid)
                        view.setTextColor(ContextCompat.getColor(context, R.color.jarvis_error))
                    }
                    is CredentialCheck.Unverifiable -> {
                        view.text = context.getString(R.string.credentials_unverifiable)
                        view.setTextColor(ContextCompat.getColor(context, R.color.jarvis_status_thinking))
                    }
                }
            }
        }
    }

    /**
     * Save every credential key. Credentials persist through
     * [SettingsCallbacks.onSaveCredentials] (live); the [OI] key has no siblings
     * on this screen, so it is persisted through
     * [SettingsCallbacks.onSaveLlmProviderSettings] with the STORED BRAIN values
     * — see the class doc's wipe warning.
     *
     * A fully-empty OAuth pair is "not configured yet" (local-first save); a
     * HALF-filled pair errors on the missing half. Salute is enforced only when
     * the Sber speech backend is active, Yandex likewise — matching the old
     * card's rule.
     */
    private fun saveCredentials() {
        val key = picovoiceKey.text.toString().trim()
        val yandexKey = yandexApiKey.text.toString().trim()
        val rawSaluteId = saluteId.text.toString()
        val rawSaluteSecret = saluteSecret.text.toString()
        val rawGigaId = gigaChatId.text.toString()
        val rawGigaSecret = gigaChatSecret.text.toString()

        // Sber issues ONE combined "authorization key"; accept it pasted into
        // either half. Normalization runs BEFORE validation so a combined key
        // in the secret field with an empty id does not read as half-filled.
        val (sId, sSec) = SberAuthorizationKey.normalize(rawSaluteId, rawSaluteSecret)
        val (gId, gSec) = SberAuthorizationKey.normalize(rawGigaId, rawGigaSecret)

        // Reveal the split so the user can see what the app understood.
        writeBackSplitCredential(saluteId, saluteSecret, rawSaluteId, rawSaluteSecret, sId, sSec)
        writeBackSplitCredential(gigaChatId, gigaChatSecret, rawGigaId, rawGigaSecret, gId, gSec)

        val activeBackend = prefs.speechBackend
        val errors = FieldValidation.validateCredentials(
            sId,
            sSec,
            gId,
            gSec,
            validateSalute = activeBackend == SpeechBackend.SBER,
        ) + if (activeBackend == SpeechBackend.YANDEX) {
            FieldValidation.validateYandexApiKey(yandexKey)
        } else {
            emptyList()
        }
        host.renderFieldErrors(errors)
        if (errors.isNotEmpty()) return

        val openAiKey = openAiApiKey.text.toString().trim()
        val openAiKeyChanged = openAiKey != prefs.openAiApiKey
        scope.launch {
            callbacks.onSaveCredentials(key, sId, sSec, gId, gSec, yandexKey)
            if (openAiKeyChanged) {
                // ⚠️ Wipe guard: pass the STORED BRAIN-owned siblings, never blanks.
                callbacks.onSaveLlmProviderSettings(
                    prefs.openAiBaseUrl,
                    prefs.openAiModel,
                    openAiKey,
                    prefs.yandexFolderId,
                )
            }
            host.toast(R.string.settings_saved)
            if (openAiKeyChanged) {
                // The [OI] key is baked into the client at graph construction.
                PendingChanges.mark(ApplyPolicies.of(OPENAI_KEY_POLICY))
            }
        }
    }

    /**
     * If either raw field held a combined Sber key, write the split halves back
     * so the fields show `(uuid, secret)`. Guarded so ordinary input (including
     * surrounding whitespace) is left untouched — only a real split rewrites.
     */
    private fun writeBackSplitCredential(
        idField: TextInputEditText,
        secretField: TextInputEditText,
        rawId: String,
        rawSecret: String,
        id: String,
        secret: String,
    ) {
        val combined = SberAuthorizationKey.split(rawSecret) != null ||
            SberAuthorizationKey.split(rawId) != null
        if (!combined) return
        if (idField.text.toString() != id) idField.setText(id)
        if (secretField.text.toString() != secret) secretField.setText(secret)
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /** Property-name form; [ApplyPolicies] normalizes it to the vault key. */
        const val OPENAI_KEY_POLICY = "openAiApiKey"
    }
}
