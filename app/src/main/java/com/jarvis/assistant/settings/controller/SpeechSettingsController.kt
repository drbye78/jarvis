package com.jarvis.assistant.settings.controller

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.settings.SpeechControls
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * «Речь» / SPEECH settings screen (settings redesign, F-C).
 *
 * Ports the old `SettingsActivity` behaviour: `setupSpeechBackendCard` +
 * `applySpeechBackendVisibility` (backend radios and the gated blocks) and
 * `setupVoiceCard` + `setupYandexVoiceControls` (per-backend voice selection).
 *
 * The backend is SEALED at graph construction, so a change is persisted through
 * [SettingsCallbacks.onSpeechBackendSelected] and marked pending
 * (ApplyPolicy.SERVICE_RESTART) — the host banner reports the restart need.
 * The voice/role/speed are read PER SENTENCE by the running graph, so they are
 * LIVE.
 *
 * CAPABILITY-DRIVEN: the ROLE and SPEED controls are gated by
 * [VoiceCatalog.capabilitiesFor] for the active backend, never by an identity
 * check, so a backend that cannot express a knob never shows it (Sber exposes
 * neither). The ROLE is additionally FAIL-CLOSED and voice-dependent: it is a
 * CLOSED dropdown of the roles the selected voice DOCUMENTS, hidden when the
 * voice documents none, and a role stored under a different voice is CLEARED
 * rather than sent as a pair the service rejects.
 *
 * [YandexVoiceSpec] is the single definition of the in-band
 * `"<voice>[:<role>][@<speed>]"` packing; this controller never hand-rolls it
 * (encode/decode drift fails silently).
 */
class SpeechSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var speechBackendGroup: RadioGroup
    private lateinit var sberVoiceBlock: View
    private lateinit var voiceGroup: RadioGroup
    private lateinit var voiceCustomInput: TextInputLayout
    private lateinit var voiceCustomId: TextInputEditText
    private lateinit var yandexVoiceBlock: View
    private lateinit var yandexVoice: MaterialAutoCompleteTextView
    private lateinit var yandexRoleLayout: View
    private lateinit var yandexRole: MaterialAutoCompleteTextView
    private lateinit var yandexSpeedBlock: View
    private lateinit var yandexSpeedBar: Slider
    private lateinit var yandexSpeedValue: TextView
    private lateinit var advancedToggle: View
    private lateinit var advancedLabel: TextView
    private lateinit var advancedChevron: ImageView
    private lateinit var advanced: View
    private lateinit var roleAdapter: ArrayAdapter<String>

    /**
     * Display label for the "no role" entry — the FIRST item in the role
     * dropdown. It is a sentinel: [commitYandexRole] maps it back to the EMPTY
     * role so the service applies its own default. (This replaces the old
     * `clear_text` end icon, which silently removed the dropdown affordance.)
     */
    private lateinit var roleNoneLabel: String

    /** True while the role field is seeded programmatically, to skip the watcher. */
    private var suppressRoleCommit = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun bind(root: View) {
        speechBackendGroup = root.findViewById(R.id.speechBackendGroup)
        sberVoiceBlock = root.findViewById(R.id.sberVoiceBlock)
        voiceGroup = root.findViewById(R.id.voiceGroup)
        voiceCustomInput = root.findViewById(R.id.voiceCustomInput)
        voiceCustomId = root.findViewById(R.id.voiceCustomId)
        yandexVoiceBlock = root.findViewById(R.id.yandexVoiceBlock)
        yandexVoice = root.findViewById(R.id.yandexVoice)
        yandexRoleLayout = root.findViewById(R.id.yandexRoleLayout)
        yandexRole = root.findViewById(R.id.yandexRole)
        yandexSpeedBlock = root.findViewById(R.id.yandexSpeedBlock)
        yandexSpeedBar = root.findViewById(R.id.yandexSpeedBar)
        yandexSpeedValue = root.findViewById(R.id.yandexSpeedValue)
        advancedToggle = root.findViewById(R.id.settingsAdvancedToggle)
        advancedLabel = root.findViewById(R.id.settingsAdvancedLabel)
        advancedChevron = root.findViewById(R.id.settingsAdvancedChevron)
        advanced = root.findViewById(R.id.settingsSpeechAdvanced)

        // Adapters + listeners first: the sync pass below seeds the role dropdown
        // and the speed slider, so both widgets must already exist.
        setupYandexVoiceControls(root)
        // Both blocks are populated unconditionally, including the hidden one:
        // visibility only toggles, so switching backend shows that backend's
        // stored values instead of an empty field.
        syncBackendFromPref()
        syncVoicesFromPref()

        speechBackendGroup.setOnCheckedChangeListener { _, checkedId ->
            val backend = if (checkedId == R.id.speechBackendYandex) {
                SpeechBackend.YANDEX
            } else {
                SpeechBackend.SBER
            }
            callbacks.onSpeechBackendSelected(backend)
            PendingChanges.mark(ApplyPolicies.of("speechBackend"))
            applyBackendVisibility(backend)
        }

        voiceGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.voiceMila) {
                prefs.ttsVoice = "Mila"
                voiceCustomInput.visibility = View.GONE
            } else {
                voiceCustomInput.visibility = View.VISIBLE
                persistCustomVoice()
            }
        }
        // Commit the custom ID on IME-done / focus loss — NOT per keystroke:
        // the running assistant resolves the voice PER SENTENCE from prefs.
        voiceCustomId.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                persistCustomVoice()
                true
            } else {
                false
            }
        }
        voiceCustomId.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) persistCustomVoice() }

        advancedToggle.setOnClickListener {
            val expand = advanced.visibility != View.VISIBLE
            advanced.visibility = if (expand) View.VISIBLE else View.GONE
            advancedChevron.animate().rotation(if (expand) 180f else 0f).start()
        }

        root.findViewById<View>(R.id.voiceTestButton).setOnClickListener {
            scope.launch {
                // Await-then-proceed: a bootstrap in progress must read as the
                // live service, not as "not running".
                val graph = host.awaitAssistantGraph() ?: return@launch
                graph.speakVoiceSample(selectedVoiceForActiveBackend())
            }
        }
    }

    override fun onResume() {
        if (!::speechBackendGroup.isInitialized) return
        syncBackendFromPref()
        syncVoicesFromPref()
    }

    override fun onStop() {
        scope.cancel()
    }

    /** Restores the backend radios and the gated blocks from the stored pref. */
    private fun syncBackendFromPref() {
        val backend = prefs.speechBackend
        speechBackendGroup.check(
            when (backend) {
                SpeechBackend.SBER -> R.id.speechBackendSber
                SpeechBackend.YANDEX -> R.id.speechBackendYandex
            },
        )
        applyBackendVisibility(backend)
    }

    /** Shows the controls belonging to [backend] and hides the other's. */
    private fun applyBackendVisibility(backend: SpeechBackend) {
        val isYandex = backend == SpeechBackend.YANDEX
        sberVoiceBlock.visibility = if (isYandex) View.GONE else View.VISIBLE
        yandexVoiceBlock.visibility = if (isYandex) View.VISIBLE else View.GONE
        applyControlVisibility(backend)
    }

    /**
     * Gates the ROLE and SPEED controls on the active backend's DECLARED
     * capabilities — not on its identity — and, for the role, on the selected
     * voice's documented roles (fail-closed). The disclosure then reports the
     * number of entries actually visible.
     */
    private fun applyControlVisibility(backend: SpeechBackend) {
        val capabilities = VoiceCatalog.capabilitiesFor(backend)
        val documentedRoles = VoiceCatalog.yandexRolesFor(activeYandexVoice())
        val showRole = SpeechControls.showRole(capabilities, documentedRoles)
        yandexRoleLayout.visibility = if (showRole) View.VISIBLE else View.GONE
        val showSpeed = SpeechControls.showSpeed(capabilities)
        yandexSpeedBlock.visibility = if (showSpeed) View.VISIBLE else View.GONE
        applyAdvancedVisibility(SpeechControls.advancedEntryCount(capabilities, documentedRoles))
        syncSpeedControl()
    }

    /**
     * Reveals the «Дополнительно» disclosure only when [entryCount] is non-zero;
     * on a backend that supports none of the advanced entries it hides the row AND
     * collapses the container, mirroring the host's disclosure rule.
     */
    private fun applyAdvancedVisibility(entryCount: Int) {
        if (entryCount > 0) {
            advancedToggle.visibility = View.VISIBLE
            advancedLabel.text =
                advancedLabel.context.getString(R.string.settings_advanced_show, entryCount)
        } else {
            advancedToggle.visibility = View.GONE
            advanced.visibility = View.GONE
            advancedChevron.rotation = 0f
        }
    }

    /** Restores both voice blocks from prefs, including the hidden one. */
    private fun syncVoicesFromPref() {
        val savedVoice = prefs.ttsVoice
        val savedIsPreset = VoiceCatalog.SBER_VOICES.any { it.id.equals(savedVoice, ignoreCase = true) }
        if (savedIsPreset) {
            voiceGroup.check(R.id.voiceMila)
            voiceCustomInput.visibility = View.GONE
        } else {
            voiceGroup.check(R.id.voiceCustom)
            voiceCustomId.setText(savedVoice)
            voiceCustomInput.visibility = View.VISIBLE
        }
        val yandexVoiceId = prefs.yandexTtsVoice.ifBlank { YandexVoiceSpec.DEFAULT_VOICE }
        yandexVoice.setText(yandexVoiceId, false)
        refreshRoleSuggestions(yandexVoiceId)
        // Fail-closed on resume too: a role persisted under an older voice must
        // not survive into a pairing the service would reject.
        clearInvalidRoleFor(yandexVoiceId)
        setRoleText(prefs.yandexTtsRole)
        applyControlVisibility(prefs.speechBackend)
    }

    /**
     * Yandex voice + role + speed controls. The voice list is a closed, documented
     * set, so it is a real dropdown; the ROLE is a CLOSED dropdown of only the
     * roles the selected voice documents; the SPEED is a slider persisted LIVE.
     */
    private fun setupYandexVoiceControls(root: View) {
        val voiceAdapter = ArrayAdapter(
            root.context,
            android.R.layout.simple_list_item_1,
            VoiceCatalog.YANDEX_VOICES.map { it.id },
        )
        yandexVoice.setAdapter(voiceAdapter)
        // The dropdown list is the whole vocabulary; the default filter would
        // hide entries as the user types, which is wrong for a closed list.
        yandexVoice.setOnClickListener { yandexVoice.showDropDown() }

        roleAdapter = ArrayAdapter(root.context, android.R.layout.simple_list_item_1)
        roleNoneLabel = root.context.getString(R.string.yandex_role_none)
        yandexRole.setAdapter(roleAdapter)
        // The field is non-editable (`inputType=none`), so a bare tap only
        // focuses it. Opening the list explicitly is what makes this read as a
        // dropdown — the same affordance the voice field installs.
        yandexRole.setOnClickListener { yandexRole.showDropDown() }
        refreshRoleSuggestions(activeYandexVoice())

        yandexVoice.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitYandexVoice()
                true
            } else {
                false
            }
        }
        yandexVoice.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitYandexVoice() }
        yandexVoice.setOnItemClickListener { _, _, _, _ -> commitYandexVoice() }

        // The role field is non-editable (`inputType=none`), so a text change is
        // a dropdown pick — commit it. The "no role" choice is a real list entry
        // (a display sentinel) that [commitYandexRole] maps back to "".
        yandexRole.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                if (!suppressRoleCommit) commitYandexRole()
            }
        })

        // The speed is LIVE (read per sentence by the graph); persist on every
        // user-driven change and keep the value label in sync.
        yandexSpeedBar.addOnChangeListener { _, value, fromUser ->
            updateSpeedLabel(value)
            if (fromUser) prefs.yandexTtsSpeed = value
        }
    }

    /**
     * Persists the Yandex speaker and refreshes/revalidates the role controls.
     *
     * VALIDATED: only a member of [VoiceCatalog.YANDEX_VOICES] is persisted
     * (matched case-insensitively and canonicalized by its catalog spelling). A
     * free-text field can therefore never store a foreign id (e.g. the Sber
     * `Mila`) that Yandex would reject with a hard `PERMISSION_DENIED`; an
     * unknown value keeps the prior valid choice, else the default. The field
     * is corrected in place so the UI never shows an unpersisted value.
     */
    private fun commitYandexVoice() {
        val entered = yandexVoice.text.toString().trim()
        val selected = VoiceCatalog.YANDEX_VOICES
            .firstOrNull { it.id.equals(entered, ignoreCase = true) }?.id
        val prior = VoiceCatalog.YANDEX_VOICES
            .firstOrNull { it.id.equals(prefs.yandexTtsVoice, ignoreCase = true) }?.id
        val resolved = selected ?: prior ?: YandexVoiceSpec.DEFAULT_VOICE
        if (entered != resolved) {
            yandexVoice.setText(resolved, false)
        }
        prefs.yandexTtsVoice = resolved
        refreshRoleSuggestions(resolved)
        clearInvalidRoleFor(resolved)
        setRoleText(prefs.yandexTtsRole)
        applyControlVisibility(prefs.speechBackend)
    }

    private fun commitYandexRole() {
        val text = yandexRole.text.toString().trim()
        // The leading "no role" entry is a display sentinel and persists as the
        // EMPTY role (the service default). `VoiceCatalog.validRoleFor` at the
        // graph and again in the TTS client would drop any label that leaked.
        prefs.yandexTtsRole = if (text == roleNoneLabel) "" else text
    }

    /**
     * Clears the stored role when the (new) [voiceId] does not document it, so a
     * stale role can never travel packed with a voice that rejects it. A valid
     * role is left untouched.
     */
    private fun clearInvalidRoleFor(voiceId: String) {
        val stored = prefs.yandexTtsRole
        val valid = VoiceCatalog.validRoleFor(voiceId, stored).orEmpty()
        if (stored.trim() == valid) return
        prefs.yandexTtsRole = valid
    }

    /** Seeds the role field without letting the watcher re-persist the same value. */
    private fun setRoleText(role: String) {
        suppressRoleCommit = true
        yandexRole.setText(role.ifBlank { roleNoneLabel }, false)
        suppressRoleCommit = false
    }

    /**
     * Role suggestions follow the selected voice (documented roles only,
     * fail-closed). The "no role" sentinel leads the list so clearing is an
     * explicit choice rather than an empty field.
     */
    private fun refreshRoleSuggestions(voiceId: String) {
        roleAdapter.clear()
        roleAdapter.add(roleNoneLabel)
        roleAdapter.addAll(VoiceCatalog.yandexRolesFor(voiceId))
    }

    /** Seeds the speed slider from the stored rate, snapped to the UI grid. */
    private fun syncSpeedControl() {
        val speed = SpeechControls.snapSpeed(prefs.yandexTtsSpeed)
        yandexSpeedBar.value = speed
        updateSpeedLabel(speed)
    }

    private fun updateSpeedLabel(speed: Float) {
        yandexSpeedValue.text = yandexSpeedValue.context.getString(R.string.yandex_speed_value, speed)
    }

    /** Current Sber voice: the preset radio wins, else the custom field. */
    private fun selectedSberVoice(): String = SettingsMapping.selectedVoiceId(
        isMilaSelected = voiceGroup.checkedRadioButtonId == R.id.voiceMila,
        customText = voiceCustomId.text.toString(),
    )

    /** Saves the custom voice ID (trimmed); blank is ignored. */
    private fun persistCustomVoice() {
        val id = voiceCustomId.text.toString().trim()
        if (id.isNotEmpty()) prefs.ttsVoice = id
    }

    /** The Yandex speaker currently shown/resolved, falling back to the stored/default. */
    private fun activeYandexVoice(): String {
        val fromUi = yandexVoice.text.toString().trim()
        return fromUi.ifBlank { prefs.yandexTtsVoice.ifBlank { YandexVoiceSpec.DEFAULT_VOICE } }
    }

    /**
     * The voice handed to «Проверить голос» for the ACTIVE backend. The probe
     * synthesizes through the running graph, whose TTS client is the one sealed
     * at construction, so previewing the other backend's voice would fail
     * confusingly. For Yandex the trio travels packed by [YandexVoiceSpec] — the
     * SINGLE encode definition — with the role validated against the catalog
     * (fail-closed), mirroring `AppGraph.voiceSource`.
     */
    private fun selectedVoiceForActiveBackend(): String = when (prefs.speechBackend) {
        SpeechBackend.SBER -> selectedSberVoice()
        SpeechBackend.YANDEX -> {
            val voice = activeYandexVoice()
            YandexVoiceSpec.join(
                voice = voice,
                role = VoiceCatalog.validRoleFor(voice, prefs.yandexTtsRole),
                speed = prefs.yandexTtsSpeed,
            )
        }
    }
}
