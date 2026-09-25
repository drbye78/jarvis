package com.jarvis.assistant.settings.controller

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsCategory
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.ui.FieldValidation
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * BRAIN («Помощник») detail screen controller (settings redesign).
 *
 * Ports the old Activity's `setupLlmProviderCard` / `bindModelGroup` /
 * `applyProviderVisibility` / `saveLlmProviderSettings` behaviour onto the new
 * `screen_settings_brain.xml`. The screen is SELF-SUFFICIENT: it reads the
 * provider pref on bind AND on resume, so the old "the provider-visibility pass
 * must run last" ordering constraint disappears rather than moving.
 *
 * The LLM provider/model/URLs are SEALED at `AppGraph` construction
 * (`ProviderSettings` builds the client at service start), so any provider or
 * model change is a SERVICE_RESTART change and is recorded in
 * [PendingChanges] to drive the host's restart banner.
 *
 * SPLIT SCREEN (frozen contract): the [OI] API key lives on ACCOUNTS while its
 * URL + model live here, and GigaChat's secret likewise. Each provider block
 * therefore carries a READ-ONLY credential-status row that reports presence and
 * taps through to ACCOUNTS — it never duplicates the input field.
 *
 * Secrets resolve through [CredentialsStore.get] (the process singleton), the
 * same accessor the old Activity used; the 3-arg ctor is unchanged by design.
 */
class BrainSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var context: Context

    private lateinit var providerGroup: RadioGroup
    private lateinit var gigaChatBlock: View
    private lateinit var yandexBlock: View
    private lateinit var openAiBlock: View
    private lateinit var yandexFolderIdLayout: View
    private lateinit var gigaChatModelGroup: RadioGroup
    private lateinit var yandexModelGroup: RadioGroup
    private lateinit var yandexFolderId: TextInputEditText
    private lateinit var openAiBaseUrl: TextInputEditText
    private lateinit var openAiModel: TextInputEditText
    private lateinit var saveButton: Button

    private lateinit var advancedContainer: View
    private lateinit var advancedToggle: View
    private lateinit var advancedLabel: TextView
    private lateinit var advancedChevron: View

    private lateinit var gigaChatStatus: TextView
    private lateinit var yandexStatus: TextView
    private lateinit var openAiStatus: TextView

    /**
     * Controllers own their coroutine scope (there is no `lifecycleScope` in
     * the frozen seam); it carries the suspend [SettingsCallbacks] save call.
     * Recreated in [onResume] when a stop→resume round-trip (the ACCOUNTS
     * tap-through) has cancelled it, so the save action keeps working.
     */
    private var scope = newScope()

    override fun bind(root: View) {
        context = root.context
        providerGroup = root.findViewById(R.id.llmProviderGroup)
        gigaChatBlock = root.findViewById(R.id.gigaChatBlock)
        yandexBlock = root.findViewById(R.id.yandexBlock)
        openAiBlock = root.findViewById(R.id.openAiBlock)
        yandexFolderIdLayout = root.findViewById(R.id.yandexFolderIdLayout)
        gigaChatModelGroup = root.findViewById(R.id.gigaChatModelGroup)
        yandexModelGroup = root.findViewById(R.id.yandexModelGroup)
        yandexFolderId = root.findViewById(R.id.yandexFolderId)
        openAiBaseUrl = root.findViewById(R.id.openAiBaseUrl)
        openAiModel = root.findViewById(R.id.openAiModel)
        saveButton = root.findViewById(R.id.saveProviderButton)
        advancedContainer = root.findViewById(R.id.settingsBrainAdvanced)
        advancedToggle = root.findViewById(R.id.settingsAdvancedToggle)
        advancedLabel = root.findViewById(R.id.settingsAdvancedLabel)
        advancedChevron = root.findViewById(R.id.settingsAdvancedChevron)
        gigaChatStatus = root.findViewById(R.id.gigaChatCredentialStatus)
        yandexStatus = root.findViewById(R.id.yandexCredentialStatus)
        openAiStatus = root.findViewById(R.id.openAiCredentialStatus)

        // Apply the STORED provider BEFORE any listener is attached, so the
        // programmatic check() can never fire the "user changed it" path.
        providerGroup.check(radioIdFor(prefs.providerType))

        // This screen owns the [OI] URL + model; the KEY lives on ACCOUNTS.
        openAiBaseUrl.setText(prefs.openAiBaseUrl)
        openAiModel.setText(prefs.openAiModel)

        // Folder id commits on IME-done / focus loss (like the old card), not
        // per keystroke — a partially typed folder must never persist.
        yandexFolderId.setText(prefs.yandexFolderId)
        yandexFolderId.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitYandexFolderId()
                true
            } else {
                false
            }
        }
        yandexFolderId.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitYandexFolderId()
        }

        // Model radios persist immediately and are service-restart scoped.
        bindModelGroup(
            gigaChatModelGroup,
            SettingsMapping.gigaChatModelIndex(prefs.gigaChatModel),
            R.id.gigaChatModelLightning,
            R.id.gigaChatModelPro,
            R.id.gigaChatModelUltra,
        ) { index ->
            prefs.gigaChatModel = SettingsMapping.gigaChatModelAt(index)
            PendingChanges.mark(ApplyPolicies.of("gigaChatModel"))
        }
        bindModelGroup(
            yandexModelGroup,
            SettingsMapping.yandexModelIndex(prefs.yandexModel),
            R.id.yandexModelAlice,
            R.id.yandexModelAliceFlash,
            R.id.yandexModelYandexGpt,
        ) { index ->
            prefs.yandexModel = SettingsMapping.yandexModelAt(index)
            PendingChanges.mark(ApplyPolicies.of("yandexModel"))
        }

        attachProviderListener()

        advancedToggle.setOnClickListener {
            setAdvancedExpanded(advancedContainer.visibility != View.VISIBLE)
        }

        // Read-only status rows: presence + a tap-through to where the keys live.
        gigaChatStatus.setOnClickListener { host.openCategory(SettingsCategory.ACCOUNTS) }
        yandexStatus.setOnClickListener { host.openCategory(SettingsCategory.ACCOUNTS) }
        openAiStatus.setOnClickListener { host.openCategory(SettingsCategory.ACCOUNTS) }

        saveButton.setOnClickListener { saveProvider() }

        applyProviderVisibility(prefs.providerType)
        refreshCredentialStatuses()
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle".
        if (!::providerGroup.isInitialized) return
        // A stop→resume round-trip (returning from the ACCOUNTS tap-through)
        // cancels the scope; revive it so the save action is not silently dead.
        if (!scope.isActive) scope = newScope()

        // Re-read the provider pref (self-sufficient controller). Re-syncing the
        // radio must not be mistaken for a user edit, so detach before check().
        val type = prefs.providerType
        val id = radioIdFor(type)
        if (providerGroup.checkedRadioButtonId != id) {
            providerGroup.setOnCheckedChangeListener(null)
            providerGroup.check(id)
            attachProviderListener()
        }
        applyProviderVisibility(type)
        refreshCredentialStatuses()
    }

    override fun onStop() {
        scope.cancel()
    }

    private fun attachProviderListener() {
        providerGroup.setOnCheckedChangeListener { _, checkedId ->
            val wire = when (checkedId) {
                R.id.providerOpenai -> "openai"
                R.id.providerYandex -> "yandex"
                else -> "gigachat"
            }
            callbacks.onLlmProviderSelected(wire)
            // The provider is baked into the client at graph construction.
            PendingChanges.mark(ApplyPolicies.of("providerType"))
            applyProviderVisibility(SettingsMapping.providerTypeFor(wire))
        }
    }

    /** The provider pref as its radio id. */
    private fun radioIdFor(type: ProviderSettings.Type): Int = when (type) {
        ProviderSettings.Type.OPENAI_COMPAT -> R.id.providerOpenai
        ProviderSettings.Type.YANDEX -> R.id.providerYandex
        ProviderSettings.Type.GIGACHAT -> R.id.providerGigachat
    }

    /**
     * Bind a three-way model `RadioGroup` to a stored pref: check the radio for
     * [storedIndex] BEFORE attaching the listener (so a programmatic `check()`
     * can never look like a user edit), then report the picked slot to
     * [onPicked]. The low/mid/high ids are parameters because the GigaChat and
     * Yandex groups differ only by their ids.
     */
    private fun bindModelGroup(
        group: RadioGroup,
        storedIndex: Int,
        lowId: Int,
        midId: Int,
        highId: Int,
        onPicked: (Int) -> Unit,
    ) {
        group.check(
            when (storedIndex) {
                1 -> midId
                2 -> highId
                else -> lowId
            },
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            onPicked(
                when (checkedId) {
                    midId -> 1
                    highId -> 2
                    else -> 0
                },
            )
        }
    }

    /** Show only the block belonging to the selected provider, plus its gated rows. */
    private fun applyProviderVisibility(type: ProviderSettings.Type) {
        gigaChatBlock.visibility = if (type == ProviderSettings.Type.GIGACHAT) View.VISIBLE else View.GONE
        openAiBlock.visibility = if (type == ProviderSettings.Type.OPENAI_COMPAT) View.VISIBLE else View.GONE
        yandexBlock.visibility = if (type == ProviderSettings.Type.YANDEX) View.VISIBLE else View.GONE
        // yandexFolderIdLayout lives in the ADVANCED container, not in
        // yandexBlock, so it must be gated on the provider independently.
        yandexFolderIdLayout.visibility = if (type == ProviderSettings.Type.YANDEX) View.VISIBLE else View.GONE
        applyDisclosure(type)
    }

    /**
     * The disclosure row exists only when the ACTIVE provider has advanced
     * settings (GigaChat 0, Yandex 1, [OI] 2). [OI] is auto-expanded because all
     * of its content is advanced — a collapsed screen would show only the radio.
     * A non-[OI] expansion is left untouched so a manual expand survives the
     * stop→resume round-trip.
     */
    private fun applyDisclosure(type: ProviderSettings.Type) {
        val count = advancedCount(type)
        advancedToggle.visibility = if (count > 0) View.VISIBLE else View.GONE
        if (count > 0) {
            advancedLabel.text = context.getString(R.string.settings_advanced_show, count)
        }
        when {
            type == ProviderSettings.Type.OPENAI_COMPAT -> setAdvancedExpanded(true)
            count == 0 -> setAdvancedExpanded(false)
            else -> Unit // Yandex: preserve the user's manual expand/collapse.
        }
    }

    /** Advanced contract-table entries for the active provider. */
    private fun advancedCount(type: ProviderSettings.Type): Int = when (type) {
        ProviderSettings.Type.GIGACHAT -> 0 // model radios are essential
        ProviderSettings.Type.YANDEX -> 1 // yandexFolderId
        ProviderSettings.Type.OPENAI_COMPAT -> 2 // base URL + model
    }

    /** Expand/collapse the advanced block; the chevron points DOWN collapsed, UP expanded. */
    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        advancedChevron.rotation = if (expanded) 180f else 0f
    }

    /**
     * Presence only — there is no "saved" flag. GigaChat authenticates with an
     * OAuth id+secret PAIR, so it counts as set only when BOTH halves are
     * present; a half-filled pair can never authenticate and must not read as
     * "set". The [OI] and Yandex keys are single values.
     *
     * The [OI] key is read from [AppPrefs.openAiApiKey] (it routes to the SAME
     * `SecretVault.KEY_OPENAI_API_KEY` slot); [CredentialsStore] exposes the
     * other vault members but not that one.
     */
    private fun refreshCredentialStatuses() {
        val store = CredentialsStore.get()
        gigaChatStatus.text = keyStatus(
            store.gigaChatClientId.isNotBlank() && store.gigaChatClientSecret.isNotBlank(),
        )
        yandexStatus.text = keyStatus(store.yandexApiKey.isNotBlank())
        openAiStatus.text = keyStatus(prefs.openAiApiKey.isNotBlank())
    }

    private fun keyStatus(isSet: Boolean): CharSequence =
        context.getString(if (isSet) R.string.settings_brain_key_set else R.string.settings_brain_key_missing)

    /** Persist the folder id only when it actually changed, so no false banner. */
    private fun commitYandexFolderId() {
        val value = yandexFolderId.text.toString().trim()
        if (value == prefs.yandexFolderId) return
        prefs.yandexFolderId = value
        PendingChanges.mark(ApplyPolicies.of("yandexFolderId"))
    }

    /**
     * The screen's primary action. Validates the [OI] endpoint, then persists
     * URL + model + folder id.
     *
     * ⚠️ WIPE GUARD: [SettingsCallbacks.onSaveLlmProviderSettings] writes ALL
     * four arguments. This screen does not display the API key (ACCOUNTS owns
     * it), so it passes the ALREADY-STORED [AppPrefs.openAiApiKey] — a blank
     * would silently erase the user's key.
     */
    private fun saveProvider() {
        val url = openAiBaseUrl.text.toString().trim()
        val model = openAiModel.text.toString().trim()
        val folderId = yandexFolderId.text.toString().trim()
        val storedKey = prefs.openAiApiKey

        host.renderFieldErrors(FieldValidation.validateLlmProvider(url, storedKey))
        if (url.isEmpty() || storedKey.isEmpty()) return

        val changed = url != prefs.openAiBaseUrl ||
            model != prefs.openAiModel ||
            folderId != prefs.yandexFolderId
        scope.launch {
            callbacks.onSaveLlmProviderSettings(url, model, storedKey, folderId)
            if (changed) PendingChanges.mark(ApplyPolicies.of("openAiBaseUrl"))
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
}
