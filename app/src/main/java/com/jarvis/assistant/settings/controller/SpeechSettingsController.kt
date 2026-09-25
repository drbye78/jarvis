package com.jarvis.assistant.settings.controller

import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.R
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
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
 * The voice is read PER SENTENCE by the running graph, so a voice/role change
 * is LIVE.
 *
 * [YandexVoiceSpec] is the single definition of the in-band `"<voice>:<role>"`
 * packing; this controller never hand-rolls it (encode/decode drift fails
 * silently).
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
    private lateinit var yandexRole: MaterialAutoCompleteTextView
    private lateinit var advancedToggle: View
    private lateinit var advancedLabel: TextView
    private lateinit var advancedChevron: ImageView
    private lateinit var advanced: View
    private lateinit var roleAdapter: ArrayAdapter<String>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun bind(root: View) {
        speechBackendGroup = root.findViewById(R.id.speechBackendGroup)
        sberVoiceBlock = root.findViewById(R.id.sberVoiceBlock)
        voiceGroup = root.findViewById(R.id.voiceGroup)
        voiceCustomInput = root.findViewById(R.id.voiceCustomInput)
        voiceCustomId = root.findViewById(R.id.voiceCustomId)
        yandexVoiceBlock = root.findViewById(R.id.yandexVoiceBlock)
        yandexVoice = root.findViewById(R.id.yandexVoice)
        yandexRole = root.findViewById(R.id.yandexRole)
        advancedToggle = root.findViewById(R.id.settingsAdvancedToggle)
        advancedLabel = root.findViewById(R.id.settingsAdvancedLabel)
        advancedChevron = root.findViewById(R.id.settingsAdvancedChevron)
        advanced = root.findViewById(R.id.settingsSpeechAdvanced)

        // Both blocks are populated unconditionally, including the hidden one:
        // visibility only toggles, so switching backend shows that backend's
        // stored values instead of an empty field.
        syncBackendFromPref()
        syncVoicesFromPref()
        setupYandexVoiceControls(root)

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
        applyAdvancedVisibility(isYandex)
    }

    /**
     * The screen's one advanced entry is `yandexTtsRole`, so the disclosure
     * exists only on the Yandex backend. On Sber it hides and the container
     * collapses, mirroring the host's "disclosure only for essential+advanced"
     * rule.
     */
    private fun applyAdvancedVisibility(isYandex: Boolean) {
        if (isYandex) {
            advancedToggle.visibility = View.VISIBLE
            advancedLabel.text =
                advancedLabel.context.getString(R.string.settings_advanced_show, ADVANCED_COUNT_YANDEX)
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
        yandexVoice.setText(prefs.yandexTtsVoice.ifBlank { YandexVoiceSpec.DEFAULT_VOICE }, false)
        yandexRole.setText(prefs.yandexTtsRole, false)
    }

    /**
     * Yandex voice + role controls. The voice list is a closed, documented set,
     * so it is a real dropdown; the ROLE is an editable combo whose suggestions
     * follow the selected voice. Both commit on IME-done / focus loss for the
     * same per-sentence reason as the Sber custom ID.
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
        yandexRole.setAdapter(roleAdapter)
        refreshRoleSuggestions(yandexVoice.text.toString())

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
        yandexRole.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitYandexRole()
                true
            } else {
                false
            }
        }
        yandexRole.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitYandexRole() }
        yandexRole.setOnItemClickListener { _, _, _, _ -> commitYandexRole() }
    }

    /** Persists the Yandex speaker (voice only) and refreshes role suggestions. */
    private fun commitYandexVoice() {
        val id = yandexVoice.text.toString().trim()
        if (id.isNotEmpty()) {
            prefs.yandexTtsVoice = id
            refreshRoleSuggestions(id)
        }
    }

    private fun commitYandexRole() {
        prefs.yandexTtsRole = yandexRole.text.toString().trim()
    }

    /** Role suggestions follow the selected voice (undocumented → full set). */
    private fun refreshRoleSuggestions(voiceId: String) {
        roleAdapter.clear()
        roleAdapter.addAll(VoiceCatalog.yandexRolesFor(voiceId))
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

    /**
     * The voice handed to «Проверить голос» for the ACTIVE backend. The probe
     * synthesizes through the running graph, whose TTS client is the one sealed
     * at construction, so previewing the other backend's voice would fail
     * confusingly. For Yandex the pair travels packed by [YandexVoiceSpec] —
     * the SINGLE encode definition — mirroring `AppGraph.voiceSource`.
     */
    private fun selectedVoiceForActiveBackend(): String = when (prefs.speechBackend) {
        SpeechBackend.SBER -> selectedSberVoice()
        SpeechBackend.YANDEX -> YandexVoiceSpec.join(
            voice = yandexVoice.text.toString().ifBlank {
                prefs.yandexTtsVoice.ifBlank { YandexVoiceSpec.DEFAULT_VOICE }
            },
            role = yandexRole.text.toString(),
        )
    }

    private companion object {
        /** The screen's advanced-entry count (`yandexTtsRole`) for the disclosure. */
        const val ADVANCED_COUNT_YANDEX = 1
    }
}
