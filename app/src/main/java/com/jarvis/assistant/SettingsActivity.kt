package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jarvis.assistant.audio.WakeWordImport
import com.jarvis.assistant.cognitive.data.MemoryMetaEntity
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.llm.CredentialCheck
import com.jarvis.assistant.llm.CredentialCheckController
import com.jarvis.assistant.llm.OAuthCredentialValidator
import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.speech.tts.YandexVoiceSpec
import com.jarvis.assistant.ui.EdgeToEdge
import com.jarvis.assistant.ui.FieldErrorRenderer
import com.jarvis.assistant.ui.FieldValidation
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import com.jarvis.assistant.util.SberAuthorizationKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Short alias for the material switch used across the Settings cards. */
private typealias MemorySwitch = com.google.android.material.materialswitch.MaterialSwitch

/**
 * Callback contract the Settings screen uses to push user input out of the UI
 * layer. Every method is implemented here and wires input into the
 * [CredentialsStore] and the live wake-word [com.jarvis.assistant.audio.HybridWakeWordDetector]
 * exposed via [GraphHolder] (when the assistant is running).
 */
interface SettingsCallbacks {
    /** Persist the user-supplied provider credentials. */
    suspend fun onSaveCredentials(
        picovoiceKey: String,
        saluteId: String,
        saluteSecret: String,
        gigaChatId: String,
        gigaChatSecret: String,
        yandexApiKey: String,
    )

    /** The LLM backend changed: "gigachat" | "openai". */
    fun onLlmProviderSelected(type: String)

    /**
     * The speech backend changed (Sber / Yandex). Persisting is the caller's
     * job; the running graph keeps the old provider until the next service
     * start, which the card's hint states.
     */
    fun onSpeechBackendSelected(backend: SpeechBackend)

    /** Persist the OpenAI-compatible endpoint settings (url/model/key). */
    suspend fun onSaveLlmProviderSettings(baseUrl: String, model: String, apiKey: String)

    /** The chosen wake-word model changed (`builtin` | `custom_bundled`). */
    fun onWakeWordSelected(modelId: String)

    /** FIXPLAN C: a validated custom Sherpa keyword was applied (blank = bundled Jarvis). */
    suspend fun onSherpaKeywordApplied(keyword: String)

    /** FIXPLAN B: the voice-stop toggle changed. */
    fun onVoiceStopToggled(enabled: Boolean)

    /** User asked to load a custom .ppn file from the device. */
    fun onLoadCustomPpn()

    /** Porcupine sensitivity changed, range 0.0–1.0. */
    fun onSensitivityChanged(value: Float)

    /** The chosen wake-word engine changed ("porcupine" | "sherpa"). */
    fun onEngineSelected(engine: String)
}

/**
 * Settings: provider credentials (Picovoice / Sber Salute / GigaChat) and
 * wake-word configuration. All persistence and detector control is delegated
 * to [SettingsCallbacks]; this Activity only owns the UI and its wiring.
 */
// The Settings surface is deliberately one screen with per-card blocks;
// splitting it into fragments would add navigation ceremony without
// reducing risk for a single-device, single-user app.
@Suppress("LargeClass")
class SettingsActivity : AppCompatActivity() {

    private lateinit var picovoiceKey: TextInputEditText
    private lateinit var saluteId: TextInputEditText
    private lateinit var saluteSecret: TextInputEditText
    private lateinit var gigaChatId: TextInputEditText
    private lateinit var gigaChatSecret: TextInputEditText
    private lateinit var saluteCheckStatus: TextView
    private lateinit var gigaChatCheckStatus: TextView

    /** Upfront validation of the mandatory Salute/GigaChat pairs (as you type). */
    private lateinit var credentialChecks: CredentialCheckController

    private lateinit var wakeWordGroup: RadioGroup
    private lateinit var wakeCustomBundledRadio: android.widget.RadioButton
    private lateinit var sherpaKeywordInput: com.google.android.material.textfield.TextInputEditText
    private lateinit var sherpaKeywordStatus: TextView
    private lateinit var voiceStopSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var sensitivityBar: SeekBar
    private lateinit var sensitivityValue: TextView

    private lateinit var llmProviderGroup: RadioGroup
    private lateinit var openAiBlock: View
    private lateinit var openAiBaseUrl: TextInputEditText
    private lateinit var openAiModel: TextInputEditText
    private lateinit var openAiApiKey: TextInputEditText

    // Speech backend (Sber / Yandex) — one choice drives ASR + TTS
    private lateinit var speechBackendGroup: RadioGroup
    private lateinit var sberCredentialsBlock: View
    private lateinit var yandexCredentialsBlock: View
    private lateinit var yandexApiKey: TextInputEditText

    private lateinit var playerGroup: RadioGroup

    private lateinit var engineGroup: RadioGroup
    private lateinit var porcupineBlock: View
    private lateinit var sherpaBlock: View

    /**
     * U7: the TextInputLayout wrapping each validated input, so a field error
     * can be attached to the field that caused it instead of raised as a
     * free-floating Toast.
     */
    private lateinit var fieldLayouts: Map<FieldValidation.Field, TextInputLayout>

    // AEC (Phase A + Phase B) card
    private lateinit var aecGroup: RadioGroup
    private lateinit var aecProbeRow: TextView
    private lateinit var aecSoftwareHint: TextView
    private lateinit var aecCaptureSwitch: com.google.android.material.materialswitch.MaterialSwitch

    // Follow-up window card
    private lateinit var followUpSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var followUpValue: TextView
    private lateinit var followUpBar: SeekBar

    // Voice (Y6) card
    private lateinit var voiceGroup: RadioGroup
    private lateinit var voiceCustomInput: View
    private lateinit var voiceCustomId: TextInputEditText
    private lateinit var sberVoiceBlock: View
    private lateinit var yandexVoiceBlock: View
    private lateinit var yandexVoice: MaterialAutoCompleteTextView
    private lateinit var yandexRole: MaterialAutoCompleteTextView

    private lateinit var appPrefs: AppPrefs

    /**
     * Real implementation backed by [CredentialsStore] and the live
     * [GraphHolder.graph]. Replaced in onCreate once [appPrefs] is ready.
     */
    private var callbacks: SettingsCallbacks = StubCallbacks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EdgeToEdge.enable(this)
        setContentView(R.layout.activity_settings)
        // Text fields live in this screen: reserve the keyboard too.
        EdgeToEdge.pad(findViewById(R.id.settingsRoot), includeIme = true)
        capColumnWidthOnWideScreens()

        appPrefs = AppPrefs(this)

        picovoiceKey = findViewById(R.id.picovoiceKey)
        saluteId = findViewById(R.id.saluteId)
        saluteSecret = findViewById(R.id.saluteSecret)
        gigaChatId = findViewById(R.id.gigaChatId)
        gigaChatSecret = findViewById(R.id.gigaChatSecret)
        saluteCheckStatus = findViewById(R.id.saluteCheckStatus)
        gigaChatCheckStatus = findViewById(R.id.gigaChatCheckStatus)
        wakeWordGroup = findViewById(R.id.wakeWordGroup)
        wakeCustomBundledRadio = findViewById(R.id.wakeCustomBundled)
        sherpaKeywordInput = findViewById(R.id.sherpaKeywordInput)
        sherpaKeywordStatus = findViewById(R.id.sherpaKeywordStatus)
        voiceStopSwitch = findViewById(R.id.voiceStopSwitch)
        sensitivityBar = findViewById(R.id.sensitivityBar)
        sensitivityValue = findViewById(R.id.sensitivityValue)

        llmProviderGroup = findViewById(R.id.llmProviderGroup)
        openAiBlock = findViewById(R.id.openAiBlock)
        openAiBaseUrl = findViewById(R.id.openAiBaseUrl)
        openAiModel = findViewById(R.id.openAiModel)
        openAiApiKey = findViewById(R.id.openAiApiKey)

        speechBackendGroup = findViewById(R.id.speechBackendGroup)
        sberCredentialsBlock = findViewById(R.id.sberCredentialsBlock)
        yandexCredentialsBlock = findViewById(R.id.yandexCredentialsBlock)
        yandexApiKey = findViewById(R.id.yandexApiKey)

        fieldLayouts = mapOf(
            FieldValidation.Field.OPENAI_BASE_URL to findViewById(R.id.openAiBaseUrlLayout),
            FieldValidation.Field.OPENAI_API_KEY to findViewById(R.id.openAiApiKeyLayout),
            FieldValidation.Field.SALUTE_ID to findViewById(R.id.saluteIdLayout),
            FieldValidation.Field.SALUTE_SECRET to findViewById(R.id.saluteSecretLayout),
            FieldValidation.Field.GIGACHAT_ID to findViewById(R.id.gigaChatIdLayout),
            FieldValidation.Field.GIGACHAT_SECRET to findViewById(R.id.gigaChatSecretLayout),
            FieldValidation.Field.YANDEX_API_KEY to findViewById(R.id.yandexApiKeyLayout),
        )

        engineGroup = findViewById(R.id.engineGroup)
        porcupineBlock = findViewById(R.id.porcupineBlock)
        sherpaBlock = findViewById(R.id.sherpaBlock)

        // Header close affordance (the theme is NoActionBar).
        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }

        // One call per card. Each method owns that card's initial state, its render
        // step and its listeners — in that order. The order is load-bearing: every
        // control is put into its stored state BEFORE its listener is attached, so a
        // programmatic `check()`/`isChecked =` can never look like a user action
        // (that is what would stop a live capture lane or rebuild the wake engine).
        setupLlmProviderCard()
        setupMusicCard()
        setupAecCard()
        setupFollowUpCard()
        setupMemoryCard()
        setupBehaviorCard()
        setupSemanticRecallCard()
        setupVoiceCard()

        callbacks = RealCallbacks()

        setupCredentialsCard()
        setupWakeWordCard()

        // LAST: the speech-backend card only toggles visibility of blocks that
        // the credentials and voice cards own, so it must run after both have
        // bound their views. Running it earlier leaves the Yandex voice block
        // (bound in setupVoiceCard) unreferenced and the card crashes on an
        // install that already has Yandex stored.
        setupSpeechBackendCard()
    }

    /**
     * Caps the reading column at [SETTINGS_COLUMN_MAX_WIDTH_DP] only when the
     * window is genuinely wider than that, so it stays centered and
     * comfortable instead of spanning a large tablet. The layout keeps
     * `layout_width="match_parent"`: a fixed dp width is NOT clamped by a
     * ScrollView, which centers an oversized child and lets it spill off both
     * edges (the regression this mirrors from the home screen). Re-applied on
     * every recreation, which covers rotation and window resizes.
     */
    private fun capColumnWidthOnWideScreens() {
        val column = findViewById<View>(R.id.settingsColumn)
        val dm = resources.displayMetrics
        val maxWidthPx = (SETTINGS_COLUMN_MAX_WIDTH_DP * dm.density).toInt()
        if (dm.widthPixels > maxWidthPx) {
            column.layoutParams = column.layoutParams.apply { width = maxWidthPx }
        }
    }

    /** A0) LLM provider card: GigaChat default, or any OpenAI-compatible endpoint. */
    private fun setupLlmProviderCard() {
        // A0) LLM provider selection. The graph consumes these prefs at
        // service start (AppGraph builds GigaChatClient or OpenAiCompatClient
        // from ProviderSettings), so a change takes effect after the next
        // service restart — the hint under the fields says exactly that.
        val isOpenAi = appPrefs.providerType == com.jarvis.assistant.config.ProviderSettings.Type.OPENAI_COMPAT
        llmProviderGroup.check(if (isOpenAi) R.id.providerOpenai else R.id.providerGigachat)
        openAiBaseUrl.setText(appPrefs.openAiBaseUrl)
        openAiModel.setText(appPrefs.openAiModel)
        openAiApiKey.setText(appPrefs.openAiApiKey)
        applyProviderVisibility(isOpenAi)

        llmProviderGroup.setOnCheckedChangeListener { _, checkedId ->
            val type = if (checkedId == R.id.providerOpenai) "openai" else "gigachat"
            callbacks.onLlmProviderSelected(type)
            applyProviderVisibility(type == "openai")
        }

        findViewById<Button>(R.id.saveProviderButton).setOnClickListener {
            saveLlmProviderSettings()
        }
    }

    /**
     * Speech backend card: Sber SaluteSpeech or Yandex SpeechKit v3.
     *
     * Both ASR and TTS move together — they are one product decision, and the
     * two providers have disjoint credential and voice namespaces, so a split
     * could pair a Sber voice ID with the Yandex client. The choice is SEALED
     * at graph construction (each provider owns its channel and auth scheme),
     * so it applies after a service restart; the card says so.
     *
     * The gated blocks below (credential + voice) are driven by
     * [applySpeechBackendVisibility], which is called ONCE here from the
     * stored pref and again on every change — never from inside a listener
     * alone, or an install that was already on Yandex would render the Sber
     * fields until the user happened to touch the radio.
     */
    private fun setupSpeechBackendCard() {
        val backend = appPrefs.speechBackend
        speechBackendGroup.check(
            when (backend) {
                SpeechBackend.SBER -> R.id.speechBackendSber
                SpeechBackend.YANDEX -> R.id.speechBackendYandex
            },
        )
        applySpeechBackendVisibility(backend)

        speechBackendGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = if (checkedId == R.id.speechBackendYandex) {
                SpeechBackend.YANDEX
            } else {
                SpeechBackend.SBER
            }
            callbacks.onSpeechBackendSelected(selected)
            applySpeechBackendVisibility(selected)
            // Switching BACK to Sber re-arms Salute probing, but re-arming
            // alone would leave the status row blank (the verdict was cleared
            // when Yandex was selected, and Idle renders as GONE) while the
            // freshly-revealed fields sit there. Probing here reproduces what
            // opening the panel already does, so the card looks the same
            // whether it was opened on Sber or switched back to it.
            if (selected == SpeechBackend.SBER) credentialChecks.checkNow()
        }
    }

    /**
     * Shows the credential and voice controls belonging to [backend] and hides
     * the other provider's. Visibility ONLY — the hidden fields keep their
     * values, so switching back and forth never discards a key the user typed
     * or a voice they chose.
     */
    private fun applySpeechBackendVisibility(backend: SpeechBackend) {
        val isYandex = backend == SpeechBackend.YANDEX
        sberCredentialsBlock.visibility = if (isYandex) View.GONE else View.VISIBLE
        yandexCredentialsBlock.visibility = if (isYandex) View.VISIBLE else View.GONE
        sberVoiceBlock.visibility = if (isYandex) View.GONE else View.VISIBLE
        yandexVoiceBlock.visibility = if (isYandex) View.VISIBLE else View.GONE
        // A stale Salute verdict must not sit under a card the user cannot see
        // (and must not read as a problem with the Yandex key). Probing is
        // stopped too, not just hidden: with Yandex selected the Salute pair is
        // unused, so sending the user's Sber OAuth credentials to Sber's token
        // endpoint would be egress on behalf of a provider the app has been
        // told not to call.
        credentialChecks.setSaluteValidationEnabled(!isYandex)
        if (isYandex) {
            saluteCheckStatus.visibility = View.GONE
            fieldLayouts[FieldValidation.Field.SALUTE_ID]?.error = null
            fieldLayouts[FieldValidation.Field.SALUTE_SECRET]?.error = null
        }
    }

    /** A1) Music card: the preferred default player (read lazily on every resolve). */
    private fun setupMusicCard() {
        // A1) Preferred default music player (Settings «Музыка» card). The
        // composition root reads this pref lazily on every resolve, so the
        // change applies to the NEXT voice command — no service restart.
        // An uninstalled preferred player degrades to auto priority in
        // MusicAppCatalog (tested), so no validation is needed here.
        playerGroup = findViewById(R.id.playerGroup)
        playerGroup.check(
            when (SettingsMapping.playerForPref(appPrefs.preferredMusicPlayer)) {
                SettingsMapping.Player.YANDEX -> R.id.playerYandex
                SettingsMapping.Player.ZVUK -> R.id.playerZvuk
                SettingsMapping.Player.VK -> R.id.playerVk
                SettingsMapping.Player.AUTO -> R.id.playerAuto
            },
        )
        playerGroup.setOnCheckedChangeListener { _, checkedId ->
            val player = when (checkedId) {
                R.id.playerYandex -> SettingsMapping.Player.YANDEX
                R.id.playerZvuk -> SettingsMapping.Player.ZVUK
                R.id.playerVk -> SettingsMapping.Player.VK
                else -> SettingsMapping.Player.AUTO
            }
            appPrefs.preferredMusicPlayer = SettingsMapping.playerPrefFor(player)
        }
    }

    /** AEC card: OFF / HARDWARE / SOFTWARE, opt-in (rebuilds AudioRecord on next start). */
    private fun setupAecCard() {
        // ------------------------------------------------------------------
        // AEC card: OFF / HARDWARE / SOFTWARE (opt-in, default OFF; the mode
        // rebuilds the AudioRecord → applies after service restart).
        // ------------------------------------------------------------------
        aecGroup = findViewById(R.id.aecGroup)
        aecProbeRow = findViewById(R.id.aecProbeRow)
        aecSoftwareHint = findViewById(R.id.aecSoftwareHint)
        aecCaptureSwitch = findViewById(R.id.aecCaptureSwitch)
        aecGroup.check(
            when (com.jarvis.assistant.audio.aec.AecMode.fromPref(appPrefs.aecMode)) {
                com.jarvis.assistant.audio.aec.AecMode.HARDWARE -> R.id.aecHardware
                com.jarvis.assistant.audio.aec.AecMode.SOFTWARE -> R.id.aecSoftware
                com.jarvis.assistant.audio.aec.AecMode.OFF -> R.id.aecOff
            }
        )
        aecProbeRow.setText(
            if (com.jarvis.assistant.audio.aec.AecProbe.staticAvailable()) {
                R.string.aec_hw_probe_available
            } else {
                R.string.aec_hw_probe_unavailable
            }
        )
        applyAecVisibility(appPrefs.aecMode)
        aecGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.aecHardware -> "hardware"
                R.id.aecSoftware -> "software"
                else -> "off"
            }
            appPrefs.aecMode = mode
            applyAecVisibility(mode)
        }
        findViewById<Button>(R.id.aecCaptureGrant).setOnClickListener {
            // MediaProjection consent → the graph's capture lane (SOFTWARE
            // mode only; the service guards it too).
            val intent = GraphHolder.graph?.playbackCapture?.createConsentIntent()
            if (intent == null) {
                Toast.makeText(this, R.string.aec_hw_probe_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, CAPTURE_REQUEST)
            }
        }
        // Capture-switch drift fix: initialize from the LIVE capture state —
        // the layout default lies after activity recreation (a capture that
        // was already running showed OFF while the lane fed the canceller).
        // Graph may be absent (service stopped): the honest default is "not
        // capturing". Set BEFORE the listener attach — a programmatic
        // isChecked=false here must not stop a running lane.
        aecCaptureSwitch.isChecked = GraphHolder.graph?.playbackCapture?.running == true
        aecCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) GraphHolder.graph?.playbackCapture?.stop()
            // Enabling alone does nothing: the Grant button runs the consent.
        }
    }

    /** Follow-up window card: switch + 2..12 s window, live-applied to the running graph. */
    private fun setupFollowUpCard() {
        // ------------------------------------------------------------------
        // Follow-up window card: switch + 2..12 s window, LIVE-applied through
        // the running graph (no service restart).
        // ------------------------------------------------------------------
        followUpSwitch = findViewById(R.id.followUpSwitch)
        followUpValue = findViewById(R.id.followUpValue)
        followUpBar = findViewById(R.id.followUpBar)
        followUpSwitch.isChecked = appPrefs.followUpEnabled
        val windowSeconds = (appPrefs.followUpWindowMs / 1000L).toInt().coerceIn(2, 12)
        followUpBar.progress = windowSeconds - 2
        updateFollowUpLabel(windowSeconds)
        followUpSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.followUpEnabled = checked
            GraphHolder.graph?.sessionManager?.setFollowUpWindow(checked, followUpSeconds())
        }
        followUpBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                updateFollowUpLabel(value + 2)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                appPrefs.followUpWindowMs = followUpSeconds()
                GraphHolder.graph?.sessionManager?.setFollowUpWindow(followUpSwitch.isChecked, followUpSeconds())
            }
        })
    }

    /** COGNITIVE_PLAN 1.8 «Память» card: the four switches + the extraction backfill action. */
    private fun setupMemoryCard() {
        // ------------------------------------------------------------------
        // COGNITIVE_PLAN 1.8: «Память» card. §12.4: EVERY cognitive default
        // is the initial value of a user-visible switch; all four switches
        // are consumed reactively by the coordinator (PrefsFlow), so a
        // toggle applies from the next turn — no restart (live-toggle
        // regression tests: PrefsFlowTest / MemoryToolsTest).
        // ------------------------------------------------------------------
        val memoryEnabledSwitch = findViewById<MemorySwitch>(R.id.memoryEnabledSwitch)
        val memoryAutoExtractSwitch = findViewById<MemorySwitch>(R.id.memoryAutoExtractSwitch)
        val memoryCloudSwitch = findViewById<MemorySwitch>(R.id.memoryCloudSwitch)
        val memorySensitiveSwitch = findViewById<MemorySwitch>(R.id.memorySensitiveSwitch)
        val backfillStatus = findViewById<TextView>(R.id.memoryBackfillStatus)

        memoryEnabledSwitch.isChecked = appPrefs.memoryEnabled
        memoryAutoExtractSwitch.isChecked = appPrefs.memoryAutoExtract
        memoryCloudSwitch.isChecked = appPrefs.memoryCloudEnabled
        memorySensitiveSwitch.isChecked = appPrefs.memorySensitiveVisible

        memoryEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.memoryEnabled = checked
        }
        memoryAutoExtractSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.memoryAutoExtract = checked
        }
        memoryCloudSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.memoryCloudEnabled = checked
        }
        memorySensitiveSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.memorySensitiveVisible = checked
        }

        findViewById<View>(R.id.memoryInspectorButton).setOnClickListener {
            startActivity(Intent(this, MemoryInspectorActivity::class.java))
        }

        val backfillButton = findViewById<Button>(R.id.memoryBackfillButton)
        lifecycleScope.launch {
            val done = GraphHolder.graph?.let { graph ->
                kotlin.runCatching {
                    graph.database.memoryMetaDao()
                        .get(com.jarvis.assistant.cognitive.data.MemoryMetaEntity.KEY_EXTRACTION_BACKFILL_DONE)
                }.getOrNull()
            } != null
            if (done) {
                backfillButton.isEnabled = false
                backfillStatus.setText(R.string.settings_memory_backfill_done)
            }
        }
        backfillButton.setOnClickListener {
            lifecycleScope.launch {
                val graph = awaitAssistantGraph() ?: return@launch
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.settings_memory_backfill_confirm_title)
                    .setMessage(R.string.settings_memory_backfill_confirm_text)
                    .setPositiveButton(R.string.settings_memory_backfill) { _, _ ->
                        lifecycleScope.launch {
                            val enqueued = kotlin.runCatching {
                                graph.cognitiveCoordinator.backfillRecent()
                            }.getOrDefault(-1)
                            when {
                                enqueued == -1 -> {
                                    backfillButton.isEnabled = false
                                    backfillStatus.setText(R.string.settings_memory_backfill_done)
                                }
                                enqueued == 0 -> backfillStatus.setText(R.string.settings_memory_backfill_none)
                                else -> {
                                    backfillButton.isEnabled = false
                                    backfillStatus.text =
                                        getString(R.string.settings_memory_backfill_started, enqueued)
                                }
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** COGNITIVE_PLAN 2.6 behaviour card: proactive switch, quiet hours, daily quota (default OFF). */
    private fun setupBehaviorCard() {
        // ------------------------------------------------------------------
        // COGNITIVE_PLAN 2.6: behaviour card (§12.4-1 — the proactive layer
        // ships DEFAULT OFF). The coordinator consumes these prefs through
        // PrefsFlow StateFlows, so a change applies live — no restart (the
        // AGENTS.md live-toggle convention; the push regression lives in
        // PrefsFlowTest).
        // ------------------------------------------------------------------
        val behaviorEnabledSwitch = findViewById<MemorySwitch>(R.id.behaviorEnabledSwitch)
        behaviorEnabledSwitch.isChecked = appPrefs.behaviorEnabled
        behaviorEnabledSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.behaviorEnabled = checked
        }

        val quietStartMinus = findViewById<Button>(R.id.behaviorQuietStartMinus)
        val quietStartPlus = findViewById<Button>(R.id.behaviorQuietStartPlus)
        val quietStartValue = findViewById<TextView>(R.id.behaviorQuietStartValue)
        val quietEndMinus = findViewById<Button>(R.id.behaviorQuietEndMinus)
        val quietEndPlus = findViewById<Button>(R.id.behaviorQuietEndPlus)
        val quietEndValue = findViewById<TextView>(R.id.behaviorQuietEndValue)
        val quotaMinus = findViewById<Button>(R.id.behaviorQuotaMinus)
        val quotaPlus = findViewById<Button>(R.id.behaviorQuotaPlus)
        val quotaValue = findViewById<TextView>(R.id.behaviorQuotaValue)

        fun renderBehaviorControls() {
            quietStartValue.text = String.format(java.util.Locale.US, "%02d:00", appPrefs.behaviorQuietStart)
            quietEndValue.text = String.format(java.util.Locale.US, "%02d:00", appPrefs.behaviorQuietEnd)
            quotaValue.text = appPrefs.behaviorDailyQuota.toString()
        }
        renderBehaviorControls()
        quietStartMinus.setOnClickListener {
            appPrefs.behaviorQuietStart = SettingsMapping.quietHour(appPrefs.behaviorQuietStart, -1)
            renderBehaviorControls()
        }
        quietStartPlus.setOnClickListener {
            appPrefs.behaviorQuietStart = SettingsMapping.quietHour(appPrefs.behaviorQuietStart, +1)
            renderBehaviorControls()
        }
        quietEndMinus.setOnClickListener {
            appPrefs.behaviorQuietEnd = SettingsMapping.quietHour(appPrefs.behaviorQuietEnd, -1)
            renderBehaviorControls()
        }
        quietEndPlus.setOnClickListener {
            appPrefs.behaviorQuietEnd = SettingsMapping.quietHour(appPrefs.behaviorQuietEnd, +1)
            renderBehaviorControls()
        }
        quotaMinus.setOnClickListener {
            appPrefs.behaviorDailyQuota = SettingsMapping.quota(appPrefs.behaviorDailyQuota, -1)
            renderBehaviorControls()
        }
        quotaPlus.setOnClickListener {
            appPrefs.behaviorDailyQuota = SettingsMapping.quota(appPrefs.behaviorDailyQuota, +1)
            renderBehaviorControls()
        }
    }

    /** COGNITIVE_PLAN Phase 3 semantic-recall half of the «Память» card: embedder selector + vectors. */
    private fun setupSemanticRecallCard() {
        // ----------------------------------------------------------------
        // COGNITIVE_PLAN Phase 3 (§11/§12.4-3/§12.4-4): semantic recall.
        // Selector AUTO/CLOUD/LOCAL/OFF (default AUTO, §12.4-3), the
        // on-device benchmark action (static probes only — no user data
        // egress), and the opt-in vector build with the §9.2 privacy
        // disclosure for the cloud branch.
        // ----------------------------------------------------------------
        val embedderLabels = mapOf(
            "AUTO" to getString(R.string.settings_semantic_embedder_auto),
            "CLOUD" to getString(R.string.settings_semantic_embedder_cloud),
            "LOCAL" to getString(R.string.settings_semantic_embedder_local),
            "OFF" to getString(R.string.settings_semantic_embedder_off),
        )
        val engineLabels = mapOf(
            "local-lexical-v1" to getString(R.string.settings_semantic_embedder_local),
            "gigachat-embeddings" to getString(R.string.settings_semantic_embedder_cloud),
        )
        val selectorButton = findViewById<Button>(R.id.embedderSelectorButton)
        val embedderStatus = findViewById<TextView>(R.id.embedderStatusText)
        val benchmarkResult = findViewById<TextView>(R.id.semanticBenchmarkResult)
        val vectorsStatus = findViewById<TextView>(R.id.semanticVectorsStatus)

        fun renderEmbedderSelector() {
            selectorButton.text = embedderLabels[appPrefs.memoryEmbedder]
        }
        renderEmbedderSelector()
        selectorButton.setOnClickListener {
            appPrefs.memoryEmbedder = SettingsMapping.nextEmbedder(appPrefs.memoryEmbedder)
            renderEmbedderSelector()
        }

        observeSemanticStatus(embedderStatus, vectorsStatus, engineLabels)
        setupSemanticBenchmarkButton(benchmarkResult, engineLabels)
        setupSemanticVectorsButton(vectorsStatus)
    }

    /** Mirrors the stored embedder verdict and the live vector-build progress into the status rows. */
    private fun observeSemanticStatus(
        embedderStatus: TextView,
        vectorsStatus: TextView,
        engineLabels: Map<String, String>,
    ) {
        lifecycleScope.launch {
            val graph = awaitAssistantGraph() ?: return@launch
            // Provenance line: benchmark verdict + stored vector count.
            val metaDao = graph.database.memoryMetaDao()
            val winner = runCatching { metaDao.get(MemoryMetaEntity.KEY_EMBEDDER_WINNER) }.getOrNull()
            val vectorEngine = runCatching { metaDao.get(MemoryMetaEntity.KEY_VECTORS_ENGINE) }.getOrNull()
            val vectorCount = runCatching {
                vectorEngine?.let { graph.database.factVectorDao().countForEngine(it) } ?: 0
            }.getOrElse { 0 }
            embedderStatus.text = if (winner == null) {
                getString(R.string.settings_semantic_status_no_winner, vectorCount)
            } else {
                getString(
                    R.string.settings_semantic_status,
                    engineLabels[winner] ?: winner,
                    vectorCount,
                )
            }
            // Backfill progress lives on the cognitive scope — mirror it here.
            graph.cognitiveCoordinator.vectorBackfill.progress.collect { p ->
                vectorsStatus.text = when {
                    p == null -> ""
                    p.running -> getString(R.string.settings_semantic_vectors_progress, p.done, p.total)
                    p.error != null -> getString(
                        R.string.settings_semantic_vectors_failed,
                        p.error.take(60),
                    )
                    else -> getString(R.string.settings_semantic_vectors_done, p.done)
                }
            }
        }
    }

    /** On-device retrieval benchmark (static probes only — no user-data egress). */
    private fun setupSemanticBenchmarkButton(
        benchmarkResult: TextView,
        engineLabels: Map<String, String>,
    ) {
        findViewById<Button>(R.id.semanticBenchmarkButton).setOnClickListener {
            benchmarkResult.setText(R.string.settings_semantic_benchmark_running)
            lifecycleScope.launch {
                val graph = awaitAssistantGraph() ?: run {
                    benchmarkResult.setText(R.string.voice_service_not_running)
                    return@launch
                }
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { graph.cognitiveCoordinator.runRetrievalBenchmark() }
                        .getOrNull()
                }
                benchmarkResult.text = if (outcome == null) {
                    getString(R.string.settings_semantic_benchmark_failed)
                } else {
                    getString(
                        R.string.settings_semantic_benchmark_result,
                        outcome.winner?.let { engineLabels[it] ?: it }
                            ?: getString(R.string.settings_semantic_no_winner_short),
                        outcome.localReport.take(160),
                    )
                }
            }
        }
    }

    /** The opt-in vector build, disclosing the §9.2 egress of the chosen engine. */
    private fun setupSemanticVectorsButton(vectorsStatus: TextView) {
        findViewById<Button>(R.id.semanticVectorsButton).setOnClickListener {
            lifecycleScope.launch {
                val graph = awaitAssistantGraph() ?: run {
                    vectorsStatus.setText(R.string.voice_service_not_running)
                    return@launch
                }
                val engineId = runCatching {
                    graph.cognitiveCoordinator.resolvedEngineId()
                }.getOrNull()
                if (engineId == null) {
                    vectorsStatus.setText(R.string.settings_semantic_vectors_no_engine)
                    return@launch
                }
                // §12.4-4/§9.2: explicit opt-in; the cloud branch discloses
                // the fact-value egress, the local branch confirms the
                // on-device-only guarantee.
                val message = if (engineId == "gigachat-embeddings") {
                    R.string.settings_semantic_vectors_confirm_cloud
                } else {
                    R.string.settings_semantic_vectors_confirm_local
                }
                AlertDialog.Builder(this@SettingsActivity)
                    .setTitle(R.string.settings_semantic_vectors_confirm_title)
                    .setMessage(message)
                    .setPositiveButton(R.string.settings_memory_backfill) { _, _ ->
                        val started = graph.cognitiveCoordinator.startVectorBuild(engineId)
                        if (!started) {
                            vectorsStatus.setText(R.string.settings_semantic_vectors_running)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /** Voice card (Y6): per-backend voice selection, resolved per sentence. */
    private fun setupVoiceCard() {
        // ------------------------------------------------------------------
        // Voice card (Y6): per-backend voice selection, resolved per sentence.
        // Sber offers the verified preset or a custom voice ID; Yandex offers
        // the closed v3 voice list plus an optional role whose suggestions
        // follow the selected voice ([VoiceCatalog.yandexRolesFor]). The voice
        // is resolved PER SENTENCE from prefs by the running graph, so a change
        // applies to the next spoken sentence — NO service restart.
        // «Проверить голос» probes through the real synthesis + player lane.
        //
        // Both blocks are populated unconditionally, including the hidden one:
        // [applySpeechBackendVisibility] only toggles visibility, so whichever
        // backend the user switches to already shows its own stored values
        // instead of an empty field.
        // ------------------------------------------------------------------
        voiceGroup = findViewById(R.id.voiceGroup)
        voiceCustomInput = findViewById(R.id.voiceCustomInput)
        voiceCustomId = findViewById(R.id.voiceCustomId)
        sberVoiceBlock = findViewById(R.id.sberVoiceBlock)
        yandexVoiceBlock = findViewById(R.id.yandexVoiceBlock)
        val savedVoice = appPrefs.ttsVoice
        val savedIsPreset = VoiceCatalog.SBER_VOICES.any {
            it.id.equals(savedVoice, ignoreCase = true)
        }
        if (savedIsPreset) {
            voiceGroup.check(R.id.voiceMila)
        } else {
            voiceGroup.check(R.id.voiceCustom)
            voiceCustomId.setText(savedVoice)
            voiceCustomInput.visibility = View.VISIBLE
        }
        voiceGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.voiceMila) {
                appPrefs.ttsVoice = "Mila"
                voiceCustomInput.visibility = View.GONE
            } else {
                voiceCustomInput.visibility = View.VISIBLE
                persistCustomVoice()
            }
        }
        // Persist the custom ID on IME-done / focus loss — NOT per keystroke:
        // the running assistant resolves the voice PER SENTENCE from prefs,
        // so typing «Bianca» used to make it try «B», «Bi», «Bia»… (same
        // commit pattern as the Sherpa keyword field).
        voiceCustomId.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                persistCustomVoice()
                true
            } else {
                false
            }
        }
        voiceCustomId.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) persistCustomVoice()
        }
        setupYandexVoiceControls()
        findViewById<Button>(R.id.voiceTestButton).setOnClickListener {
            lifecycleScope.launch {
                // Await-then-proceed: a bootstrap in progress no longer reads
                // as "service not running" — the await returns the live graph.
                val graph = awaitAssistantGraph() ?: return@launch
                graph.speakVoiceSample(selectedVoiceForActiveBackend())
            }
        }
    }

    /**
     * Yandex voice + role controls.
     *
     * The voice list is a closed, documented set, so it is a real dropdown
     * (filtered to nothing typed — the user picks, they do not invent an ID).
     * The ROLE is an editable-combo whose suggestions are narrowed to the roles
     * the selected voice documents ([VoiceCatalog.yandexRolesFor]): the service
     * rejects a role/voice pair it does not support, so a pure dropdown would
     * trap the user on a value they may not use — the presets are suggestions,
     * and free text is still allowed for undocumented voices.
     *
     * Both commit on IME-done / focus loss for the same per-sentence reason as
     * the Salute custom ID — the running graph re-reads the pref between
     * sentences, so a per-keystroke write would speak from half-typed values.
     */
    private fun setupYandexVoiceControls() {
        yandexVoice = findViewById(R.id.yandexVoice)
        yandexRole = findViewById(R.id.yandexRole)

        val voiceAdapter = android.widget.ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            VoiceCatalog.YANDEX_VOICES.map { it.id },
        )
        yandexVoice.setAdapter(voiceAdapter)
        // The dropdown list is the whole vocabulary; the filter that
        // AutoCompleteTextView installs by default would also hide entries as
        // the user types, which is wrong for a closed list.
        yandexVoice.setOnClickListener { yandexVoice.showDropDown() }
        yandexVoice.setText(
            appPrefs.yandexTtsVoice.ifBlank { YandexVoiceSpec.DEFAULT_VOICE },
            false,
        )

        // Role suggestions are VOICE-specific ([VoiceCatalog.yandexRolesFor]):
        // the service rejects a pair it does not document, so offering
        // `whisper` under `alena` would invite a failure. The field stays an
        // editable combo — these are suggestions, not a whitelist — so the
        // adapter is re-pointed rather than the typed text being rewritten.
        val roleAdapter = android.widget.ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_1,
        )
        yandexRole.setAdapter(roleAdapter)
        fun refreshRoleSuggestions(voiceId: String) {
            roleAdapter.clear()
            roleAdapter.addAll(VoiceCatalog.yandexRolesFor(voiceId))
        }
        refreshRoleSuggestions(yandexVoice.text.toString())
        yandexRole.setText(appPrefs.yandexTtsRole, false)

        // Keep the raw (un-trimmed) text through the commit helpers so the
        // pref never stores a half-typed trailing space.
        fun commitVoice() {
            val id = yandexVoice.text.toString().trim()
            if (id.isNotEmpty()) {
                appPrefs.yandexTtsVoice = id
                refreshRoleSuggestions(id)
            }
        }

        fun commitRole() {
            appPrefs.yandexTtsRole = yandexRole.text.toString().trim()
        }

        yandexVoice.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitVoice()
                true
            } else {
                false
            }
        }
        yandexVoice.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitVoice() }
        yandexVoice.setOnItemClickListener { _, _, _, _ -> commitVoice() }
        yandexRole.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitRole()
                true
            } else {
                false
            }
        }
        yandexRole.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitRole() }
        yandexRole.setOnItemClickListener { _, _, _, _ -> commitRole() }
    }

    /** A) Provider credentials: stored values, live validation status and the save action. */
    private fun setupCredentialsCard() {
        // Pre-fill credential fields from the keystore-backed store.
        picovoiceKey.setText(CredentialsStore.get().picovoiceKey)
        saluteId.setText(CredentialsStore.get().saluteClientId)
        saluteSecret.setText(CredentialsStore.get().saluteClientSecret)
        gigaChatId.setText(CredentialsStore.get().gigaChatClientId)
        gigaChatSecret.setText(CredentialsStore.get().gigaChatClientSecret)
        yandexApiKey.setText(CredentialsStore.get().yandexApiKey)

        // A2) Upfront validation of the mandatory credential pairs. The
        // controller debounces typing, dedupes confirmed-Ok pairs and discards
        // stale verdicts; the validator probes the same OAuth endpoint the
        // token manager uses, so "valid" here means the next token fetch
        // succeeds. Picovoice is optional (engine-scoped) and never probed.
        credentialChecks = CredentialCheckController(
            validator = OAuthCredentialValidator(),
            scope = lifecycleScope,
        )
        attachCredentialWatchers()
        // The fields were pre-filled from the store ABOVE the watchers'
        // attachment point, so the population setText events never reached
        // the controller — its inputs stayed blank and checkNow() (the
        // button AND the create-time health check below) silently resolved
        // to Idle with the status rows hidden. Feed the current values
        // explicitly so the probe sees the SAVED pair. A saved pair is
        // normalized too, so a combined key stored by an older build still
        // probes correctly.
        val (saluteIdValue, saluteSecretValue) = SberAuthorizationKey.normalize(
            saluteId.text.toString(),
            saluteSecret.text.toString(),
        )
        credentialChecks.onSaluteInput(saluteIdValue, saluteSecretValue)
        val (gigaIdValue, gigaSecretValue) = SberAuthorizationKey.normalize(
            gigaChatId.text.toString(),
            gigaChatSecret.text.toString(),
        )
        credentialChecks.onGigaChatInput(gigaIdValue, gigaSecretValue)
        lifecycleScope.launch {
            credentialChecks.states.collect { state ->
                renderCheckStatus(
                    saluteCheckStatus,
                    state[CredentialCheckController.Service.SALUTE],
                )
                renderCheckStatus(
                    gigaChatCheckStatus,
                    state[CredentialCheckController.Service.GIGACHAT],
                )
            }
        }
        findViewById<Button>(R.id.checkCredentialsButton).setOnClickListener {
            credentialChecks.checkNow()
        }
        // Opening the panel is a health check for the SAVED pair too.
        credentialChecks.checkNow()

        // A) Save provider credentials. Saving stays local-first (works
        // offline); the status rows above tell the truth about validity, and
        // save triggers a fresh verdict for whatever is being persisted.
        findViewById<Button>(R.id.saveCredentialsButton).setOnClickListener {
            credentialChecks.checkNow()
            saveCredentials()
        }
    }

    /** B) Wake word card: model, imported .ppn, custom Sherpa keyword, voice stop, engine, sensitivity. */
    private fun setupWakeWordCard() {
        // Pre-select the current wake-word model. An imported .ppn writes
        // `custom_user`, which has NO radio of its own — it is represented by
        // the custom radio. The old restore only knew builtin/custom_bundled,
        // so an imported word restored onto the CUSTOM_BUNDLED radio and was
        // silently disabled by the next toggle (imported-word fix).
        wakeWordGroup.check(
            if (WakeWordModelUi.isBuiltinRadio(appPrefs.wakeWordModel)) {
                R.id.wakeBuiltin
            } else {
                R.id.wakeCustomBundled
            },
        )
        renderCustomWakeCaption()

        // Pre-set the sensitivity slider.
        sensitivityBar.max = 100
        sensitivityBar.progress = (appPrefs.wakeSensitivity * 100).toInt()
        updateSensitivityLabel(appPrefs.wakeSensitivity)

        // B) Wake word selection (built-in "Jarvis" or a custom model —
        // bundled keywords OR an imported .ppn: the mapping keeps an import,
        // so re-checking the custom radio cannot silently downgrade it).
        wakeWordGroup.setOnCheckedChangeListener { _, checkedId ->
            val customSelected = checkedId == R.id.wakeCustomBundled
            callbacks.onWakeWordSelected(
                WakeWordModelUi.modelForSelection(customSelected, appPrefs.customWakeWordPath),
            )
        }

        // B) Load a custom .ppn from the device.
        findViewById<Button>(R.id.loadCustomButton).setOnClickListener {
            callbacks.onLoadCustomPpn()
        }

        // FIXPLAN C: custom Sherpa wake keyword. Live validation uses the REAL
        // BPE tokenizer; a word that cannot be encoded is refused before it can
        // save (the runtime build would otherwise surface a Failed detector).
        // Persist + reconfigure on IME-done / focus loss — not per keystroke
        // (each apply rebuilds the native engine).
        sherpaKeywordInput.setText(appPrefs.sherpaCustomKeyword)
        renderKeywordStatus(appPrefs.sherpaCustomKeyword)
        sherpaKeywordInput.addTextChangedListener(
            textWatcher {
                renderKeywordStatus(sherpaKeywordInput.text.toString())
            }
        )
        sherpaKeywordInput.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                applySherpaKeyword()
                true
            } else {
                false
            }
        }
        sherpaKeywordInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) applySherpaKeyword()
        }

        // FIXPLAN B: voice stop (applies from the next turn — the session
        // state collector re-reads the pref on every state change).
        voiceStopSwitch.isChecked = appPrefs.voiceStopEnabled
        voiceStopSwitch.setOnCheckedChangeListener { _, checked ->
            appPrefs.voiceStopEnabled = checked
            callbacks.onVoiceStopToggled(checked)
        }

        // B2) Engine selection (Porcupine vs Sherpa-ONNX). Restore the saved
        // engine, reflect it in the radio + the shown control block.
        val savedEngine = appPrefs.wakeWordEngine
        engineGroup.check(
            if (savedEngine == "sherpa") R.id.engineSherpa else R.id.enginePorcupine,
        )
        applyEngineVisibility(savedEngine)

        engineGroup.setOnCheckedChangeListener { _, checkedId ->
            val engine = if (checkedId == R.id.engineSherpa) "sherpa" else "porcupine"
            callbacks.onEngineSelected(engine)
        }

        // B) Sensitivity slider: SeekBar 0..100 → wake engine 0.0..1.0.
        // The expensive native engine rebuild is deferred to onStopTrackingTouch
        // so it runs once, not on every progress tick.
        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                updateSensitivityLabel(value / 100f)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val v = (bar?.progress ?: 0) / 100f
                callbacks.onSensitivityChanged(v)
            }
        })
    }

    /** Feed the credential controller on every keystroke in the 4 fields. */
    private fun attachCredentialWatchers() {
        val saluteWatcher = textWatcher {
            val (id, secret) = SberAuthorizationKey.normalize(
                saluteId.text.toString(),
                saluteSecret.text.toString(),
            )
            credentialChecks.onSaluteInput(id, secret)
        }
        saluteId.addTextChangedListener(saluteWatcher)
        saluteSecret.addTextChangedListener(saluteWatcher)
        val gigaWatcher = textWatcher {
            val (id, secret) = SberAuthorizationKey.normalize(
                gigaChatId.text.toString(),
                gigaChatSecret.text.toString(),
            )
            credentialChecks.onGigaChatInput(id, secret)
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
                view.text = getString(R.string.credentials_checking)
                view.setTextColor(ContextCompat.getColor(this, R.color.jarvis_on_surface_variant))
            }
            is CredentialCheckController.UiState.Verdict -> {
                view.visibility = View.VISIBLE
                when (val check = state.check) {
                    CredentialCheck.Valid -> {
                        view.text = getString(R.string.credentials_ok)
                        view.setTextColor(ContextCompat.getColor(this, R.color.jarvis_status_listening))
                    }
                    is CredentialCheck.Invalid -> {
                        view.text = check.httpCode?.let {
                            getString(R.string.credentials_invalid_http, it)
                        } ?: getString(R.string.credentials_invalid)
                        view.setTextColor(ContextCompat.getColor(this, R.color.jarvis_error))
                    }
                    is CredentialCheck.Unverifiable -> {
                        view.text = getString(R.string.credentials_unverifiable)
                        view.setTextColor(ContextCompat.getColor(this, R.color.jarvis_status_thinking))
                    }
                }
            }
        }
    }

    private fun saveLlmProviderSettings() {
        val url = openAiBaseUrl.text.toString().trim()
        val model = openAiModel.text.toString().trim()
        val key = openAiApiKey.text.toString().trim()
        // U7: refuse on the OFFENDING FIELD instead of a Toast. The Toast
        // named no field and vanished on its own, so the user could not tell
        // which of the three inputs was wrong.
        renderFieldErrors(FieldValidation.validateLlmProvider(url, key))
        if (url.isEmpty() || key.isEmpty()) return
        lifecycleScope.launch {
            callbacks.onSaveLlmProviderSettings(url, model, key)
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    /** Show the OpenAI-compatible fields only when that provider is selected. */
    private fun applyProviderVisibility(isOpenAi: Boolean) {
        openAiBlock.visibility = if (isOpenAi) View.VISIBLE else View.GONE
    }

    private fun saveCredentials() {
        val key = picovoiceKey.text.toString().trim()
        val yandexKey = yandexApiKey.text.toString().trim()
        val rawSaluteId = saluteId.text.toString()
        val rawSaluteSecret = saluteSecret.text.toString()
        val rawGigaId = gigaChatId.text.toString()
        val rawGigaSecret = gigaChatSecret.text.toString()

        // Sber issues ONE combined "authorization key"; accept it pasted into
        // either half. Normalization runs BEFORE validation so a combined key
        // in the secret field with an empty id does not read as a half-filled
        // pair. (The live watchers already normalize; this is the save-path
        // guarantee for whatever is in the fields right now.)
        val (sId, sSec) = SberAuthorizationKey.normalize(rawSaluteId, rawSaluteSecret)
        val (gId, gSec) = SberAuthorizationKey.normalize(rawGigaId, rawGigaSecret)

        // UX: reveal the split so the user can see what the app understood.
        // Only when a combined key was actually split; the write-back triggers
        // the watchers, which re-feed the SAME normalized pair — no loop.
        writeBackSplitCredential(saluteId, saluteSecret, rawSaluteId, rawSaluteSecret, sId, sSec)
        writeBackSplitCredential(gigaChatId, gigaChatSecret, rawGigaId, rawGigaSecret, gId, gSec)

        // U7: a HALF-filled OAuth pair can never authenticate, and the error
        // belongs on the missing half. A fully-empty pair is "not configured
        // yet", not an error — saving stays local-first. Salute is only checked
        // while the Sber speech backend is active: on Yandex its fields are
        // hidden and unused, and an error on an invisible field would block the
        // save of the credentials that ARE in play.
        val activeBackend = appPrefs.speechBackend
        val errors = FieldValidation.validateCredentials(
            sId,
            sSec,
            gId,
            gSec,
            validateSalute = activeBackend == SpeechBackend.SBER,
        ) + if (activeBackend == SpeechBackend.YANDEX) {
            // The mirror rule for Yandex: its key is mandatory exactly when its
            // backend is selected, since the client cannot speak without it.
            FieldValidation.validateYandexApiKey(yandexKey)
        } else {
            emptyList()
        }
        renderFieldErrors(errors)
        if (errors.isNotEmpty()) return

        lifecycleScope.launch {
            callbacks.onSaveCredentials(key, sId, sSec, gId, gSec, yandexKey)
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
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

    /**
     * U7: attach validation failures to their fields and clear the rest, so a
     * corrected form stops showing the previous message. Success stays a
     * Toast — it has no field to attach to.
     */
    private fun renderFieldErrors(errors: List<FieldValidation.FieldError>) {
        FieldErrorRenderer.render(errors, fieldLayouts, this)
    }

    private fun updateSensitivityLabel(value: Float) {
        sensitivityValue.text = getString(R.string.sensitivity_value, value)
    }

    /**
     * Imported-word fix: when a user .ppn is loaded, the custom radio's
     * caption names the imported file, so the single custom radio honestly
     * represents BOTH custom flavors (bundled / imported).
     */
    private fun renderCustomWakeCaption() {
        val importedPath = appPrefs.customWakeWordPath.trim()
        if (importedPath.isNotEmpty()) {
            wakeCustomBundledRadio.text =
                getString(R.string.wake_word_custom_user_imported, java.io.File(importedPath).name)
        }
    }

    /** SOFTWARE hint only matters in software mode. */
    private fun applyAecVisibility(mode: String) {
        aecSoftwareHint.visibility = if (mode == "software") View.VISIBLE else View.GONE
    }

    private fun followUpSeconds(): Long = SettingsMapping.followUpSeconds(followUpBar.progress)

    /** Current custom-ID text, or Mila when blank. */
    private fun selectedVoice(): String = SettingsMapping.selectedVoiceId(
        isMilaSelected = voiceGroup.checkedRadioButtonId == R.id.voiceMila,
        customText = voiceCustomId.text.toString(),
    )

    /**
     * The voice to hand to the «Проверить голос» probe for the ACTIVE backend.
     *
     * The probe synthesizes through the running graph, whose TTS client is the
     * one sealed at construction — so previewing the other backend's voice
     * would send an ID that client cannot speak and fail confusingly. This
     * mirrors `AppGraph.voiceSource` (including the in-band role packing) so
     * the probe hears exactly what the assistant will say.
     */
    private fun selectedVoiceForActiveBackend(): String =
        when (appPrefs.speechBackend) {
            SpeechBackend.SBER -> selectedVoice()

            SpeechBackend.YANDEX -> YandexVoiceSpec.join(
                voice = yandexVoice.text.toString().ifBlank {
                    appPrefs.yandexTtsVoice.ifBlank { YandexVoiceSpec.DEFAULT_VOICE }
                },
                role = yandexRole.text.toString(),
            )
        }

    /** Saves the custom voice ID (trimmed); blank is ignored. */
    private fun persistCustomVoice() {
        val id = voiceCustomId.text.toString().trim()
        if (id.isNotEmpty()) appPrefs.ttsVoice = id
    }

    private fun updateFollowUpLabel(seconds: Int) {
        followUpValue.text = getString(R.string.followup_seconds, seconds)
    }

    /** Show the controls for the active engine, hide the other. */
    private fun applyEngineVisibility(engine: String) {
        val isSherpa = SettingsMapping.isSherpaEngine(engine)
        porcupineBlock.visibility = if (isSherpa) View.GONE else View.VISIBLE
        sherpaBlock.visibility = if (isSherpa) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------
    // Graph-ready gating: during the ~1-min bootstrap GraphHolder.graph is
    // null, and these handlers used to toast «Запустите ассистента» and read
    // as broken while the service was in fact STARTING. A bounded await
    // distinguishes "bootstrapping" (new honest toast) from "stopped" (the
    // existing not-running message).
    // ------------------------------------------------------------------

    /**
     * Awaits the live graph (bounded); toasts the honest state and returns
     * null when the service is stopped or still starting.
     */
    private suspend fun awaitAssistantGraph(): com.jarvis.assistant.di.AppGraph? {
        val service = GraphHolder.service
        return when (
            val outcome = com.jarvis.assistant.di.awaitGraphReady(
                graphNow = GraphHolder.graph,
                ready = service?.graphReady,
                serviceAlive = service != null,
                timeoutMs = GRAPH_READY_TIMEOUT_MS,
            )
        ) {
            is com.jarvis.assistant.di.GraphReadyOutcome.Ready -> {
                // The service can die while we were awaiting; re-validate so a
                // completed-but-shutdown deferred is never mistaken for a live graph.
                val stillAlive = GraphHolder.service != null && GraphHolder.graph === outcome.graph
                if (stillAlive) {
                    outcome.graph
                } else {
                    Toast.makeText(this, R.string.voice_service_not_running, Toast.LENGTH_SHORT).show()
                    null
                }
            }
            com.jarvis.assistant.di.GraphReadyOutcome.Bootstrapping -> {
                Toast.makeText(this, R.string.service_bootstrapping_toast, Toast.LENGTH_SHORT).show()
                null
            }
            com.jarvis.assistant.di.GraphReadyOutcome.Stopped -> {
                Toast.makeText(this, R.string.voice_service_not_running, Toast.LENGTH_SHORT).show()
                null
            }
        }
    }

    // ------------------------------------------------------------------
    // FIXPLAN C: custom Sherpa wake keyword
    // ------------------------------------------------------------------

    /** Lazy tokenizer over the bundled BPE vocab (null → validation is off). */
    private val keywordTokenizer: com.jarvis.assistant.audio.BpeTokenizer? by lazy {
        com.jarvis.assistant.audio.BpeTokenizer.fromAsset(this, "sherpa_kws/bpe.model")
    }

    private fun renderKeywordStatus(text: String) {
        val keyword = text.trim()
        if (keyword.isEmpty()) {
            // Blank = bundled Jarvis — always valid.
            sherpaKeywordStatus.visibility = View.GONE
            return
        }
        val encodable = keywordTokenizer?.tokenizeKeywordPhrase(keyword) != null
        sherpaKeywordStatus.visibility = View.VISIBLE
        if (encodable) {
            sherpaKeywordStatus.text = getString(R.string.settings_sherpa_keyword_ok, keyword)
            sherpaKeywordStatus.setTextColor(
                ContextCompat.getColor(this, R.color.jarvis_status_listening),
            )
        } else {
            sherpaKeywordStatus.text = getString(R.string.sherpa_keyword_invalid)
            sherpaKeywordStatus.setTextColor(
                ContextCompat.getColor(this, R.color.jarvis_error),
            )
        }
    }

    /** Validate, persist and reconfigure — only when the keyword is encodable. */
    private fun applySherpaKeyword() {
        val keyword = sherpaKeywordInput.text.toString().trim()
        if (keyword.isNotEmpty() && keywordTokenizer?.tokenizeKeywordPhrase(keyword) == null) {
            // Invalid: refuse loudly but keep the text so the user can edit.
            Toast.makeText(this, R.string.sherpa_keyword_invalid, Toast.LENGTH_LONG).show()
            return
        }
        if (keyword == appPrefs.sherpaCustomKeyword) return // no change
        lifecycleScope.launch {
            callbacks.onSherpaKeywordApplied(keyword)
            Toast.makeText(
                this@SettingsActivity,
                if (keyword.isEmpty()) {
                    R.string.sherpa_keyword_applied_default
                } else {
                    R.string.sherpa_keyword_applied
                },
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            // AEC Phase B: feed the consented projection to the running
            // graph's playback-capture lane (SOFTWARE mode only).
            val graph = GraphHolder.graph
            if (graph == null) {
                // F8: the playback-capture consent returned after the
                // service was stopped — the old toast claimed "credentials
                // saved", which is wrong on two counts.
                Toast.makeText(this, R.string.aec_service_not_running, Toast.LENGTH_SHORT).show()
            } else if (graph.aecMode != com.jarvis.assistant.audio.aec.AecMode.SOFTWARE) {
                Toast.makeText(this, R.string.aec_hw_probe_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                // Routed through the service so the mediaProjection FGS type
                // is promoted before the capture AudioRecord is built —
                // Android 10+ throws SecurityException without the type
                // (API 34+ enforces it even at getMediaProjection time).
                GraphHolder.service?.startPlaybackCapture(resultCode, data)
                aecCaptureSwitch.isChecked = true
            }
            return
        }
        if (requestCode == PPN_REQUEST && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            // L2: only a .ppn file is valid.
            if (!WakeWordImport.isPpnFileName(uri.lastPathSegment)) {
                Toast.makeText(
                    this,
                    R.string.error_ppn_file,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            // A provider may hand out a non-persistable grant; that is not fatal
            // for a one-shot copy, so it is reported and the copy proceeds.
            // (L4: the URI is only read once, below, so the grant is released
            // again immediately after.)
            runCatching { contentResolver.takePersistableUriPermission(uri, takeFlags) }
                .onFailure { Timber.w("wake-word import: the URI grant is not persistable") }
            // L3: copy into a private app file (sandboxed, explicit mode). The
            // copy lands in a temp file and replaces the model only once
            // verified, so a failed import cannot point the detector at a
            // missing/truncated file nor destroy a previously working model —
            // and the outcome is reported instead of silently skipped.
            val destination = getFileStreamPath(WakeWordImport.PPN_FILE_NAME)
            val outcome = WakeWordImport.install(
                source = runCatching { contentResolver.openInputStream(uri) }.getOrNull(),
                destination = destination,
                openOutput = { file -> openFileOutput(file.name, Context.MODE_PRIVATE) },
            )
            when (outcome) {
                is WakeWordImport.Outcome.Copied -> {
                    appPrefs.customWakeWordPath = destination.absolutePath
                    appPrefs.wakeWordModel = "custom_user"
                    // Reconfigure off the UI thread.
                    lifecycleScope.launch(Dispatchers.Default) {
                        GraphHolder.graph?.reconfigureWakeWord()
                    }
                }

                WakeWordImport.Outcome.NoSource,
                is WakeWordImport.Outcome.Failed,
                -> {
                    Timber.w("wake-word import %s", outcome.describe())
                    Toast.makeText(
                        this,
                        R.string.error_ppn_import,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            runCatching { contentResolver.releasePersistableUriPermission(uri, takeFlags) }
                .onFailure { Timber.w("wake-word import: releasing the URI grant failed") }
        }
    }

    /** Wires Settings input into [CredentialsStore] and the live detector. */
    private inner class RealCallbacks : SettingsCallbacks {
        override suspend fun onSaveCredentials(
            picovoiceKey: String,
            saluteId: String,
            saluteSecret: String,
            gigaChatId: String,
            gigaChatSecret: String,
            yandexApiKey: String,
        ) {
            CredentialsStore.get().picovoiceKey = picovoiceKey
            CredentialsStore.get().saluteClientId = saluteId
            CredentialsStore.get().saluteClientSecret = saluteSecret
            CredentialsStore.get().gigaChatClientId = gigaChatId
            CredentialsStore.get().gigaChatClientSecret = gigaChatSecret
            CredentialsStore.get().yandexApiKey = yandexApiKey
            // Force a token refresh so a changed Picovoice/Sber key applies now.
            GraphHolder.graph?.tokenManager?.invalidate()
            // Apply a changed Picovoice key live to the wake-word engine (this
            // method is suspend, so reconfigure can be awaited directly).
            GraphHolder.graph?.reconfigureWakeWord()
        }

        override fun onSpeechBackendSelected(backend: SpeechBackend) {
            // Pref only. The composition root seals the backend at construction
            // (each provider owns its channel AND its auth scheme, so there is
            // no live path), which is exactly what the card's restart hint
            // tells the user.
            appPrefs.speechBackend = backend
        }

        override fun onLlmProviderSelected(type: String) {
            appPrefs.providerType = if (type == "openai") {
                com.jarvis.assistant.config.ProviderSettings.Type.OPENAI_COMPAT
            } else {
                com.jarvis.assistant.config.ProviderSettings.Type.GIGACHAT
            }
        }

        override suspend fun onSaveLlmProviderSettings(baseUrl: String, model: String, apiKey: String) {
            appPrefs.openAiBaseUrl = baseUrl
            appPrefs.openAiModel = model.ifBlank {
                com.jarvis.assistant.config.ProviderSettings.DEFAULT.openAiModel
            }
            appPrefs.openAiApiKey = apiKey
        }

        override fun onWakeWordSelected(modelId: String) {
            appPrefs.wakeWordModel = modelId
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.reconfigureWakeWord()
            }
        }

        override suspend fun onSherpaKeywordApplied(keyword: String) {
            appPrefs.sherpaCustomKeyword = keyword
            GraphHolder.graph?.reconfigureWakeWord()
        }

        override fun onVoiceStopToggled(enabled: Boolean) {
            // COGNITIVE_PLAN 0.2: the pref alone is NOT enough. The stop phrase
            // is baked into the ENGINE keyword set at build time (Sherpa) and
            // into the dedicated stop lane's build requirement (Porcupine), so
            // the toggle must rebuild the live engine — the same path the
            // engine/model/sensitivity callbacks use. The pref re-read by the
            // session state collector only re-arms the LANE on the next state
            // change; without this rebuild, 3 of the 4 engine×toggle
            // combinations stayed stale until a restart.
            //
            // A5: the state collector fires on STATE CHANGE only, so re-arm the
            // lane for the current state too — otherwise enabling voice stop
            // while the assistant is THINKING/SPEAKING did nothing until the
            // turn ended (the rebuild's own tail re-arm reads the flag this
            // sets).
            GraphHolder.graph?.sessionManager?.reapplyVoiceStopLane()
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.reconfigureWakeWord()
            }
        }

        override fun onLoadCustomPpn() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream"))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, PPN_REQUEST)
        }

        override fun onEngineSelected(engine: String) {
            appPrefs.wakeWordEngine = engine
            applyEngineVisibility(engine)
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.reconfigureWakeWord()
            }
        }

        override fun onSensitivityChanged(value: Float) {
            appPrefs.wakeSensitivity = value
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.reconfigureWakeWord()
            }
        }
    }

    /** No-op stand-in until onCreate replaces it with [RealCallbacks]; every
     *  invocation is logged so a pre-init settings tap is visible (P5.3). */
    private object StubCallbacks : SettingsCallbacks {
        private fun notReady(name: String) {
            Timber.w("Settings: callback %s invoked before the graph is ready — ignored", name)
        }

        override suspend fun onSaveCredentials(
            picovoiceKey: String,
            saluteId: String,
            saluteSecret: String,
            gigaChatId: String,
            gigaChatSecret: String,
            yandexApiKey: String,
        ) {
            notReady("onSaveCredentials")
        }

        override fun onSpeechBackendSelected(backend: SpeechBackend) {
            notReady("onSpeechBackendSelected")
        }

        override fun onLlmProviderSelected(type: String) {
            notReady("onLlmProviderSelected")
        }

        override suspend fun onSaveLlmProviderSettings(baseUrl: String, model: String, apiKey: String) {
            notReady("onSaveLlmProviderSettings")
        }

        override fun onWakeWordSelected(modelId: String) {
            notReady("onWakeWordSelected")
        }

        override suspend fun onSherpaKeywordApplied(keyword: String) {
            notReady("onSherpaKeywordApplied")
        }

        override fun onVoiceStopToggled(enabled: Boolean) {
            notReady("onVoiceStopToggled")
        }

        override fun onLoadCustomPpn() {
            notReady("onLoadCustomPpn")
        }

        override fun onEngineSelected(engine: String) {
            notReady("onEngineSelected")
        }

        override fun onSensitivityChanged(value: Float) {
            notReady("onSensitivityChanged")
        }
    }

    private companion object {
        const val PPN_REQUEST = 1002
        const val SETTINGS_COLUMN_MAX_WIDTH_DP = 760
        const val CAPTURE_REQUEST = 1003

        /**
         * Bounded wait for the graph bootstrap (~1 min worst case on
         * Kirin-class devices). 45 s keeps the await shorter than the user's
         * patience while comfortably covering a normal build.
         */
        const val GRAPH_READY_TIMEOUT_MS = 45_000L
    }
}
