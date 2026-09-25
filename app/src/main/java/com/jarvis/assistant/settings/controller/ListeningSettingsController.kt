package com.jarvis.assistant.settings.controller

import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.WakeWordModelUi
import com.jarvis.assistant.audio.BpeTokenizer
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.audio.aec.AecProbe
import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.PendingChanges
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * LISTENING («Слушание») detail screen controller (settings redesign, F-B).
 *
 * The redesigned screen MERGES three old cards — «Wake word» + «Эхоподавление»
 * + «Продолжение диалога» — into one. This controller ports the old Activity's
 * `setupWakeWordCard` / `setupAecCard` / `setupFollowUpCard` and the small
 * render helpers (`applyEngineVisibility`, `applyAecVisibility`,
 * `updateSensitivityLabel`, `updateFollowUpLabel`, `renderCustomWakeCaption`,
 * `renderKeywordStatus`, `applySherpaKeyword`).
 *
 * LEVELS (frozen control→destination table):
 *  - ESSENTIAL (above the disclosure): `voiceStopSwitch` and `followUpSwitch`.
 *  - ADVANCED (behind «Дополнительно», always present — 7 entries), grouped by
 *    FEATURE so the old cards stay legible: Активация (engine, wake word,
 *    imported .ppn path, Sherpa keyword, sensitivity), Эхоподавление (AEC mode),
 *    Продолжение диалога (follow-up window length).
 *
 * THE DISCLOSURE IS ALWAYS SHOWN here (unlike BRAIN/SPEECH, where it depends on
 * the selected provider): LISTENING has 7 advanced entries regardless of the
 * current engine/mode, so the row never disappears — it just collapses/expands.
 *
 * SELF-SUFFICIENT: the screen reads the stored wake-word/engine/AEC prefs both
 * in [bind] and in [onResume]. That is what makes the host-owned `.ppn` import
 * work: `loadCustomButton` only calls [SettingsHost.importCustomPpn]; the host
 * runs the picker + `onActivityResult`, stores the file and writes the wake-word
 * prefs, then the controller re-reads them here. This controller NEVER receives
 * an Activity result for the `.ppn` (the frozen seam has no such hook).
 *
 * LIVE vs SEALED:
 *  - AEC mode is SEALED at `AppGraph` construction (the AudioRecord must be
 *    rebuilt), so picking a mode marks `aecMode` pending a SERVICE_RESTART.
 *  - Follow-up is LIVE: the window is pushed to the running session through the
 *    graph ([SettingsHost.awaitAssistantGraph]) — the same live binder call the
 *    old card made, so the "applies immediately" hint stays true.
 *  - Wake word / engine / sensitivity / keyword go through [SettingsCallbacks],
 *    whose host implementations persist AND rebuild the native engine.
 *
 * The controller owns its [CoroutineScope] because the frozen seam has no
 * `lifecycleScope`; it is cancelled in [onStop] and revived in [onResume] so a
 * stop→resume round-trip (returning from the `.ppn` or capture-grant picker)
 * does not leave the suspend save / live binder calls silently dead.
 */
class ListeningSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var context: Context

    // --- Essential (above the disclosure) ---
    private lateinit var voiceStopSwitch: SwitchMaterial
    private lateinit var followUpSwitch: MaterialSwitch

    // --- Активация (activation) ---
    private lateinit var engineGroup: RadioGroup
    private lateinit var porcupineBlock: View
    private lateinit var sherpaBlock: View
    private lateinit var wakeWordGroup: RadioGroup
    private lateinit var wakeCustomBundledRadio: RadioButton
    private lateinit var sherpaKeywordInput: TextInputEditText
    private lateinit var sherpaKeywordStatus: TextView
    private lateinit var sensitivityValue: TextView
    private lateinit var sensitivityBar: SeekBar

    // --- Эхоподавление (echo cancellation) ---
    private lateinit var aecGroup: RadioGroup
    private lateinit var aecProbeRow: TextView
    private lateinit var aecSoftwareHint: TextView
    private lateinit var aecCaptureSwitch: MaterialSwitch
    private lateinit var aecCaptureGrant: Button

    // --- Продолжение диалога (follow-up window length) ---
    private lateinit var followUpValue: TextView
    private lateinit var followUpBar: SeekBar

    // --- Disclosure ---
    private lateinit var advancedContainer: View
    private lateinit var advancedToggle: View
    private lateinit var advancedLabel: TextView
    private lateinit var advancedChevron: View

    /**
     * True while a control is re-synced from prefs. A programmatic `check()` /
     * `isChecked =` still fires its listener, and letting that run would look
     * like a user edit: a voice-stop re-sync would rebuild the native engine,
     * and an AEC re-sync would mark a pending restart the user never asked for.
     */
    private var suppressCallbacks = false

    /**
     * Owned scope (the seam has no `lifecycleScope`). Carries the suspend
     * [SettingsCallbacks.onSherpaKeywordApplied] save and the two live graph
     * calls (follow-up binder, playback-capture stop/state). Recreated in
     * [onResume] after [onStop] cancelled it.
     */
    private var scope = newScope()

    /**
     * Lazy tokenizer over the bundled BPE vocab; null turns validation OFF.
     * Built on first use, which is always after [bind] assigned [context].
     */
    private val keywordTokenizer: BpeTokenizer? by lazy {
        BpeTokenizer.fromAsset(context, KEYWORD_ASSET)
    }

    override fun bind(root: View) {
        context = root.context

        bindDisclosure(root)
        bindEssentialControls(root)
        bindActivationControls(root)
        bindSherpaKeywordControls(root)
        bindAecControls(root)
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle".
        if (!::voiceStopSwitch.isInitialized) return
        // A stop→resume round-trip (the .ppn / capture pickers) cancels the
        // scope; revive it so the suspend save and live binder calls keep working.
        if (!scope.isActive) scope = newScope()

        suppressCallbacks = true
        try {
            voiceStopSwitch.isChecked = prefs.voiceStopEnabled
            followUpSwitch.isChecked = prefs.followUpEnabled
            followUpBar.progress = windowProgress()
            updateFollowUpLabel(windowSeconds())
            // The host-owned .ppn import writes wakeWordModel/customWakeWordPath;
            // re-check the radio so the imported word is not shown as "builtin".
            engineGroup.check(engineRadioId())
            wakeWordGroup.check(wakeWordRadioId())
            renderCustomWakeCaption()
            aecGroup.check(aecRadioId())
        } finally {
            suppressCallbacks = false
        }
        applyEngineVisibility(prefs.wakeWordEngine)
        applyAecVisibility(prefs.aecMode)
        renderKeywordStatus(sherpaKeywordInput.text.toString())
        // The capture lane runs in the service; mirror its live state so the
        // switch does not lie after a return from the consent dialog.
        syncCaptureRunning()
    }

    override fun onStop() {
        scope.cancel()
    }

    /** ESSENTIAL controls: voice stop (FIXPLAN B) + the follow-up switch/window. */
    private fun bindEssentialControls(root: View) {
        // FIXPLAN B voice stop. The callback owns persistence AND the live
        // engine rebuild (the stop phrase is baked into the keyword set), so the
        // controller deliberately does not touch the pref itself.
        voiceStopSwitch = root.findViewById(R.id.voiceStopSwitch)
        voiceStopSwitch.isChecked = prefs.voiceStopEnabled
        voiceStopSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            callbacks.onVoiceStopToggled(checked)
        }

        // The follow-up switch is essential; its LENGTH is advanced below.
        // Follow-up has no callback in the frozen seam, so the pref is written
        // here and the running session is updated through the graph.
        followUpSwitch = root.findViewById(R.id.followUpSwitch)
        followUpSwitch.isChecked = prefs.followUpEnabled
        followUpSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            prefs.followUpEnabled = checked
            applyFollowUpLive()
        }

        followUpValue = root.findViewById(R.id.followUpValue)
        followUpBar = root.findViewById(R.id.followUpBar)
        followUpBar.progress = windowProgress()
        updateFollowUpLabel(windowSeconds())
        followUpBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                updateFollowUpLabel(value + WINDOW_OFFSET_SECONDS)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                // Persist only on release: the label follows the drag live, but
                // the pref/binder write happens once (SettingsMapping clamps).
                prefs.followUpWindowMs = SettingsMapping.followUpSeconds(followUpBar.progress)
                applyFollowUpLive()
            }
        })
    }

    /** The Активация group: engine, wake word, imported .ppn, sensitivity. */
    private fun bindActivationControls(root: View) {
        engineGroup = root.findViewById(R.id.engineGroup)
        porcupineBlock = root.findViewById(R.id.porcupineBlock)
        sherpaBlock = root.findViewById(R.id.sherpaBlock)
        engineGroup.check(engineRadioId())
        applyEngineVisibility(prefs.wakeWordEngine)
        engineGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val engine = if (checkedId == R.id.engineSherpa) ENGINE_SHERPA else ENGINE_PORCUPINE
            callbacks.onEngineSelected(engine)
            // Apply locally as well as through the callback: the visibility of
            // the two blocks is this screen's own state, not the session's.
            applyEngineVisibility(engine)
        }

        wakeWordGroup = root.findViewById(R.id.wakeWordGroup)
        wakeCustomBundledRadio = root.findViewById(R.id.wakeCustomBundled)
        wakeWordGroup.check(wakeWordRadioId())
        renderCustomWakeCaption()
        wakeWordGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val customSelected = checkedId == R.id.wakeCustomBundled
            // Keep an imported .ppn (custom_user) when the custom radio is
            // re-picked; WakeWordModelUi owns that mapping so a radio toggle can
            // never silently downgrade an imported word to the bundled set.
            callbacks.onWakeWordSelected(
                WakeWordModelUi.modelForSelection(customSelected, prefs.customWakeWordPath),
            )
        }

        // The host owns the picker + onActivityResult; the result is consumed by
        // onResume's re-read, never by an Activity-result hook here.
        root.findViewById<Button>(R.id.loadCustomButton).setOnClickListener {
            host.importCustomPpn()
        }

        // SeekBar 0..100 → engine 0.0..1.0. The expensive native rebuild is
        // deferred to onStopTrackingTouch so it runs once per gesture.
        sensitivityValue = root.findViewById(R.id.sensitivityValue)
        sensitivityBar = root.findViewById(R.id.sensitivityBar)
        sensitivityBar.max = SENSITIVITY_MAX
        sensitivityBar.progress = (prefs.wakeSensitivity * SENSITIVITY_MAX).toInt()
        updateSensitivityLabel(prefs.wakeSensitivity)
        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                updateSensitivityLabel(value / SENSITIVITY_MAX.toFloat())
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                callbacks.onSensitivityChanged(sensitivityBar.progress / SENSITIVITY_MAX.toFloat())
            }
        })
    }

    /** The custom Sherpa keyword sub-block (FIXPLAN C). */
    private fun bindSherpaKeywordControls(root: View) {
        sherpaKeywordInput = root.findViewById(R.id.sherpaKeywordInput)
        sherpaKeywordStatus = root.findViewById(R.id.sherpaKeywordStatus)
        sherpaKeywordInput.setText(prefs.sherpaCustomKeyword)
        renderKeywordStatus(prefs.sherpaCustomKeyword)
        // Status follows every keystroke (cheap, pure tokenizer); persistence +
        // the native rebuild are deferred to IME-done / focus loss because each
        // apply rebuilds the engine.
        sherpaKeywordInput.doAfterTextChanged { editable ->
            renderKeywordStatus(editable?.toString().orEmpty())
        }
        sherpaKeywordInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                applySherpaKeyword()
                true
            } else {
                false
            }
        }
        sherpaKeywordInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySherpaKeyword()
        }
    }

    /** The Эхоподавление group: AEC mode + the playback-capture affordances. */
    private fun bindAecControls(root: View) {
        aecGroup = root.findViewById(R.id.aecGroup)
        aecProbeRow = root.findViewById(R.id.aecProbeRow)
        aecSoftwareHint = root.findViewById(R.id.aecSoftwareHint)
        aecCaptureSwitch = root.findViewById(R.id.aecCaptureSwitch)
        aecCaptureGrant = root.findViewById(R.id.aecCaptureGrant)

        aecGroup.check(aecRadioId())
        aecProbeRow.setText(
            if (AecProbe.staticAvailable()) {
                R.string.aec_hw_probe_available
            } else {
                R.string.aec_hw_probe_unavailable
            },
        )
        applyAecVisibility(prefs.aecMode)
        aecGroup.setOnCheckedChangeListener { _, checkedId ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val mode = when (checkedId) {
                R.id.aecHardware -> AEC_HARDWARE
                R.id.aecSoftware -> AEC_SOFTWARE
                else -> AEC_OFF
            }
            prefs.aecMode = mode
            // SEALED at graph construction: the AudioRecord is built from the
            // mode, so this whole screen's AEC choice needs a SERVICE_RESTART.
            PendingChanges.mark(ApplyPolicies.of("aecMode"))
            applyAecVisibility(mode)
        }

        // The grant button is the only controller-side start path: the host runs
        // the MediaProjection consent (and the service promotes the FGS type).
        aecCaptureGrant.setOnClickListener { host.requestPlaybackCapture() }
        aecCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            // Enabling alone does nothing — the Grant button runs the consent.
            // Only unchecking has a controller-side effect: stop the running lane.
            if (!checked) stopPlaybackCapture()
        }
    }

    /**
     * The disclosure row is ALWAYS visible on LISTENING (7 advanced entries).
     * The chevron points DOWN collapsed, UP expanded.
     */
    private fun bindDisclosure(root: View) {
        advancedContainer = root.findViewById(R.id.settingsListeningAdvanced)
        advancedToggle = root.findViewById(R.id.settingsAdvancedToggle)
        advancedLabel = root.findViewById(R.id.settingsAdvancedLabel)
        advancedChevron = root.findViewById(R.id.settingsAdvancedChevron)
        advancedToggle.visibility = View.VISIBLE
        advancedLabel.text = context.getString(R.string.settings_advanced_show, ADVANCED_COUNT)
        advancedToggle.setOnClickListener {
            setAdvancedExpanded(advancedContainer.visibility != View.VISIBLE)
        }
    }

    private fun setAdvancedExpanded(expanded: Boolean) {
        advancedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
        advancedChevron.rotation = if (expanded) 180f else 0f
    }

    /** The stored engine as its radio id (comparison lives in SettingsMapping). */
    private fun engineRadioId(): Int =
        if (SettingsMapping.isSherpaEngine(prefs.wakeWordEngine)) R.id.engineSherpa else R.id.enginePorcupine

    /**
     * The stored wake-word model as its radio id. An imported `.ppn`
     * (`custom_user`) has no radio of its own; it restores onto the CUSTOM radio
     * through [WakeWordModelUi], which knows that two prefs share that slot.
     */
    private fun wakeWordRadioId(): Int =
        if (WakeWordModelUi.isBuiltinRadio(prefs.wakeWordModel)) R.id.wakeBuiltin else R.id.wakeCustomBundled

    /** The stored AEC mode as its radio id; unknown values degrade to OFF. */
    private fun aecRadioId(): Int = when (AecMode.fromPref(prefs.aecMode)) {
        AecMode.HARDWARE -> R.id.aecHardware
        AecMode.SOFTWARE -> R.id.aecSoftware
        AecMode.OFF -> R.id.aecOff
    }

    /** SeekBar progress for the stored window: 0..10 ⇒ 2..12 s. */
    private fun windowProgress(): Int = windowSeconds() - WINDOW_OFFSET_SECONDS

    /** Stored follow-up window in whole seconds, clamped to the slider's 2..12 s range. */
    private fun windowSeconds(): Int = (prefs.followUpWindowMs / 1000L).toInt().coerceIn(2, 12)

    /** Show the controls for the active engine, hide the other. */
    private fun applyEngineVisibility(engine: String) {
        val isSherpa = SettingsMapping.isSherpaEngine(engine)
        porcupineBlock.visibility = if (isSherpa) View.GONE else View.VISIBLE
        sherpaBlock.visibility = if (isSherpa) View.VISIBLE else View.GONE
    }

    /**
     * Gate the AEC rows by mode. The hardware probe is only meaningful for
     * HARDWARE; the software hint and both capture rows belong to SOFTWARE (the
     * capture lane is software-only — the service rejects a capture grant in any
     * other mode too).
     */
    private fun applyAecVisibility(mode: String) {
        val aec = AecMode.fromPref(mode)
        aecProbeRow.visibility = if (aec == AecMode.HARDWARE) View.VISIBLE else View.GONE
        aecSoftwareHint.visibility = if (aec == AecMode.SOFTWARE) View.VISIBLE else View.GONE
        aecCaptureSwitch.visibility = if (aec == AecMode.SOFTWARE) View.VISIBLE else View.GONE
        aecCaptureGrant.visibility = if (aec == AecMode.SOFTWARE) View.VISIBLE else View.GONE
    }

    private fun updateSensitivityLabel(value: Float) {
        sensitivityValue.text = context.getString(R.string.sensitivity_value, value)
    }

    private fun updateFollowUpLabel(seconds: Int) {
        followUpValue.text = context.getString(R.string.followup_seconds, seconds)
    }

    /**
     * Imported-word fix: when a user `.ppn` is loaded, the custom radio's
     * caption names the imported file, so the single custom radio honestly
     * represents BOTH custom flavors (bundled / imported). With no import the
     * default bundled caption is restored, so a cleared path cannot leave a
     * stale file name on screen.
     */
    private fun renderCustomWakeCaption() {
        val importedName = prefs.customWakeWordPath.trim().takeIf { it.isNotEmpty() }?.let { File(it).name }
        wakeCustomBundledRadio.text = if (importedName == null) {
            context.getString(R.string.wake_word_custom_bundled)
        } else {
            context.getString(R.string.wake_word_custom_user_imported, importedName)
        }
    }

    /**
     * Validate the typed keyword with the REAL bundled BPE tokenizer and render
     * the verdict. Blank = bundled «Jarvis» (always valid, row hidden); a word
     * the vocab cannot fully segment would spot nothing, so it is rendered as a
     * hard error before it can be saved.
     */
    private fun renderKeywordStatus(text: String) {
        val keyword = text.trim()
        if (keyword.isEmpty()) {
            sherpaKeywordStatus.visibility = View.GONE
            return
        }
        val encodable = keywordTokenizer?.tokenizeKeywordPhrase(keyword) != null
        sherpaKeywordStatus.visibility = View.VISIBLE
        if (encodable) {
            sherpaKeywordStatus.text = context.getString(R.string.settings_sherpa_keyword_ok, keyword)
            sherpaKeywordStatus.setTextColor(ContextCompat.getColor(context, R.color.jarvis_status_listening))
        } else {
            sherpaKeywordStatus.setText(R.string.sherpa_keyword_invalid)
            sherpaKeywordStatus.setTextColor(ContextCompat.getColor(context, R.color.jarvis_error))
        }
    }

    /**
     * Validate, then persist + reconfigure through the suspend callback — only
     * when the keyword is encodable. A no-op when the value did not change, so
     * a focus-loss after IME-done does not rebuild the engine twice.
     */
    private fun applySherpaKeyword() {
        val keyword = sherpaKeywordInput.text.toString().trim()
        if (keyword.isNotEmpty() && keywordTokenizer?.tokenizeKeywordPhrase(keyword) == null) {
            // Invalid: refuse loudly but keep the text so the user can edit.
            host.toast(R.string.sherpa_keyword_invalid)
            return
        }
        if (keyword == prefs.sherpaCustomKeyword) return
        scope.launch {
            callbacks.onSherpaKeywordApplied(keyword)
            renderKeywordStatus(keyword)
            host.toast(
                if (keyword.isEmpty()) {
                    R.string.sherpa_keyword_applied_default
                } else {
                    R.string.sherpa_keyword_applied
                },
            )
        }
    }

    /**
     * Push the follow-up switch + window to the running session. Follow-up is
     * LIVE and has no callback in the frozen seam, so this is the only path that
     * keeps the "opens immediately" promise; a stopped service simply has
     * nothing to update (the pref is already stored).
     */
    private fun applyFollowUpLive() {
        val enabled = followUpSwitch.isChecked
        val windowMs = SettingsMapping.followUpSeconds(followUpBar.progress)
        scope.launch {
            host.awaitAssistantGraph()?.sessionManager?.setFollowUpWindow(enabled, windowMs)
        }
    }

    /** Stop the running playback-capture lane (unchecking the capture switch). */
    private fun stopPlaybackCapture() {
        scope.launch {
            host.awaitAssistantGraph()?.playbackCapture?.stop()
        }
    }

    /**
     * Mirror the LIVE capture-lane state onto the switch. The layout default lies
     * after a return from the consent dialog (a capture that is already running
     * would show OFF), so the state is read from the graph, not the XML.
     */
    private fun syncCaptureRunning() {
        scope.launch {
            val running = host.awaitAssistantGraph()?.playbackCapture?.running == true
            if (aecCaptureSwitch.isChecked != running) aecCaptureSwitch.isChecked = running
        }
    }

    private fun newScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private companion object {
        /**
         * Advanced contract-table entries on this merged screen: wakeWordEngine,
         * wakeWordModel, customWakeWordPath, sherpaCustomKeyword, wakeSensitivity,
         * aecMode, followUpWindowMs. (The capture grant is an action, not a
         * setting, so it is not counted.)
         */
        const val ADVANCED_COUNT = 7

        /** Callback wire values for the wake-word engine. */
        const val ENGINE_SHERPA = "sherpa"
        const val ENGINE_PORCUPINE = "porcupine"

        /** Pref wire values for the AEC mode. */
        const val AEC_OFF = "off"
        const val AEC_HARDWARE = "hardware"
        const val AEC_SOFTWARE = "software"

        /** Relative asset path of the bundled BPE vocab used for keyword validation. */
        const val KEYWORD_ASSET = "sherpa_kws/bpe.model"

        /** SeekBar range for the sensitivity slider (0..100 → 0.0..1.0). */
        const val SENSITIVITY_MAX = 100

        /** Slider position 0..10 maps to 2..12 s; this is the 2 s offset. */
        const val WINDOW_OFFSET_SECONDS = 2
    }
}
