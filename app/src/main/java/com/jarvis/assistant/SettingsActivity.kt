package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.llm.CredentialCheck
import com.jarvis.assistant.llm.CredentialCheckController
import com.jarvis.assistant.llm.OAuthCredentialValidator
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

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
    )

    /** The LLM backend changed: "gigachat" | "openai". */
    fun onLlmProviderSelected(type: String)

    /** Persist the OpenAI-compatible endpoint settings (url/model/key). */
    suspend fun onSaveLlmProviderSettings(baseUrl: String, model: String, apiKey: String)

    /** The chosen wake-word model changed (`builtin` | `custom_bundled`). */
    fun onWakeWordSelected(modelId: String)

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
    private lateinit var sensitivityBar: SeekBar
    private lateinit var sensitivityValue: TextView

    private lateinit var llmProviderGroup: RadioGroup
    private lateinit var openAiBlock: View
    private lateinit var openAiBaseUrl: TextInputEditText
    private lateinit var openAiModel: TextInputEditText
    private lateinit var openAiApiKey: TextInputEditText

    private lateinit var playerGroup: RadioGroup

    private lateinit var engineGroup: RadioGroup
    private lateinit var porcupineBlock: View
    private lateinit var sherpaBlock: View

    // AEC (Phase A + Phase B) card
    private lateinit var aecGroup: RadioGroup
    private lateinit var aecProbeRow: TextView
    private lateinit var aecSoftwareHint: TextView
    private lateinit var aecCaptureSwitch: com.google.android.material.materialswitch.MaterialSwitch

    // Follow-up window card
    private lateinit var followUpSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var followUpValue: TextView
    private lateinit var followUpBar: SeekBar

    private lateinit var appPrefs: AppPrefs

    /**
     * Real implementation backed by [CredentialsStore] and the live
     * [GraphHolder.graph]. Replaced in onCreate once [appPrefs] is ready.
     */
    private var callbacks: SettingsCallbacks = StubCallbacks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        appPrefs = AppPrefs(this)

        picovoiceKey = findViewById(R.id.picovoiceKey)
        saluteId = findViewById(R.id.saluteId)
        saluteSecret = findViewById(R.id.saluteSecret)
        gigaChatId = findViewById(R.id.gigaChatId)
        gigaChatSecret = findViewById(R.id.gigaChatSecret)
        saluteCheckStatus = findViewById(R.id.saluteCheckStatus)
        gigaChatCheckStatus = findViewById(R.id.gigaChatCheckStatus)
        wakeWordGroup = findViewById(R.id.wakeWordGroup)
        sensitivityBar = findViewById(R.id.sensitivityBar)
        sensitivityValue = findViewById(R.id.sensitivityValue)

        llmProviderGroup = findViewById(R.id.llmProviderGroup)
        openAiBlock = findViewById(R.id.openAiBlock)
        openAiBaseUrl = findViewById(R.id.openAiBaseUrl)
        openAiModel = findViewById(R.id.openAiModel)
        openAiApiKey = findViewById(R.id.openAiApiKey)

        engineGroup = findViewById(R.id.engineGroup)
        porcupineBlock = findViewById(R.id.porcupineBlock)
        sherpaBlock = findViewById(R.id.sherpaBlock)

        // Pre-fill credential fields from the keystore-backed store.
        picovoiceKey.setText(CredentialsStore.get().picovoiceKey)
        saluteId.setText(CredentialsStore.get().saluteClientId)
        saluteSecret.setText(CredentialsStore.get().saluteClientSecret)
        gigaChatId.setText(CredentialsStore.get().gigaChatClientId)
        gigaChatSecret.setText(CredentialsStore.get().gigaChatClientSecret)

        // Pre-select the current wake-word model.
        wakeWordGroup.check(
            if (appPrefs.wakeWordModel == "builtin") R.id.wakeBuiltin else R.id.wakeCustomBundled,
        )

        // Pre-set the sensitivity slider.
        sensitivityBar.max = 100
        sensitivityBar.progress = (appPrefs.wakeSensitivity * 100).toInt()
        updateSensitivityLabel(appPrefs.wakeSensitivity)

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

        // A1) Preferred default music player (Settings «Музыка» card). The
        // composition root reads this pref lazily on every resolve, so the
        // change applies to the NEXT voice command — no service restart.
        // An uninstalled preferred player degrades to auto priority in
        // MusicAppCatalog (tested), so no validation is needed here.
        playerGroup = findViewById(R.id.playerGroup)
        playerGroup.check(
            when (appPrefs.preferredMusicPlayer) {
                "ru.yandex.music", "com.yandex.music" -> R.id.playerYandex
                "com.zvooq.openplay" -> R.id.playerZvuk
                "com.vk.music" -> R.id.playerVk
                else -> R.id.playerAuto
            },
        )
        playerGroup.setOnCheckedChangeListener { _, checkedId ->
            appPrefs.preferredMusicPlayer = when (checkedId) {
                R.id.playerYandex -> "ru.yandex.music"
                R.id.playerZvuk -> "com.zvooq.openplay"
                R.id.playerVk -> "com.vk.music"
                else -> "auto"
            }
        }

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
        aecCaptureSwitch.setOnCheckedChangeListener { _, checked ->
            if (!checked) GraphHolder.graph?.playbackCapture?.stop()
            // Enabling alone does nothing: the Grant button runs the consent.
        }

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

        callbacks = RealCallbacks()

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

        // Close / back affordance (theme is NoActionBar).
        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }

        // A) Save provider credentials. Saving stays local-first (works
        // offline); the status rows above tell the truth about validity, and
        // save triggers a fresh verdict for whatever is being persisted.
        findViewById<Button>(R.id.saveCredentialsButton).setOnClickListener {
            credentialChecks.checkNow()
            saveCredentials()
        }

        // B) Wake word selection (built-in "Jarvis" or bundled custom model).
        wakeWordGroup.setOnCheckedChangeListener { _, checkedId ->
            val modelId = if (checkedId == R.id.wakeCustomBundled) "custom_bundled" else "builtin"
            callbacks.onWakeWordSelected(modelId)
        }

        // B) Load a custom .ppn from the device.
        findViewById<Button>(R.id.loadCustomButton).setOnClickListener {
            callbacks.onLoadCustomPpn()
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
            credentialChecks.onSaluteInput(saluteId.text.toString(), saluteSecret.text.toString())
        }
        saluteId.addTextChangedListener(saluteWatcher)
        saluteSecret.addTextChangedListener(saluteWatcher)
        val gigaWatcher = textWatcher {
            credentialChecks.onGigaChatInput(gigaChatId.text.toString(), gigaChatSecret.text.toString())
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
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.error_base_url, Toast.LENGTH_SHORT).show()
            return
        }
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
        val sId = saluteId.text.toString().trim()
        val sSec = saluteSecret.text.toString().trim()
        val gId = gigaChatId.text.toString().trim()
        val gSec = gigaChatSecret.text.toString().trim()

        lifecycleScope.launch {
            callbacks.onSaveCredentials(key, sId, sSec, gId, gSec)
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateSensitivityLabel(value: Float) {
        sensitivityValue.text = getString(R.string.sensitivity_value, value)
    }

    /** SOFTWARE hint only matters in software mode. */
    private fun applyAecVisibility(mode: String) {
        aecSoftwareHint.visibility = if (mode == "software") View.VISIBLE else View.GONE
    }

    private fun followUpSeconds(): Long = ((followUpBar.progress + 2).toLong()).coerceIn(2, 12) * 1000L

    private fun updateFollowUpLabel(seconds: Int) {
        followUpValue.text = getString(R.string.followup_seconds, seconds)
    }

    /** Show the controls for the active engine, hide the other. */
    private fun applyEngineVisibility(engine: String) {
        val isSherpa = engine == "sherpa"
        porcupineBlock.visibility = if (isSherpa) View.GONE else View.VISIBLE
        sherpaBlock.visibility = if (isSherpa) View.VISIBLE else View.GONE
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
                graph.playbackCapture.start(resultCode, data)
                aecCaptureSwitch.isChecked = true
            }
            return
        }
        if (requestCode == PPN_REQUEST && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            // L2: only a .ppn file is valid.
            if (uri.lastPathSegment?.endsWith(".ppn", ignoreCase = true) != true) {
                Toast.makeText(
                    this,
                    R.string.error_ppn_file,
                    Toast.LENGTH_SHORT,
                ).show()
                return
            }
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            // L3: copy into a private app file (sandboxed, explicit mode).
            val dst = getFileStreamPath("user_wake.ppn")
            contentResolver.openInputStream(uri)?.use { input ->
                openFileOutput("user_wake.ppn", Context.MODE_PRIVATE).use { output ->
                    input.copyTo(output)
                }
            }
            appPrefs.customWakeWordPath = dst.absolutePath
            appPrefs.wakeWordModel = "custom_user"
            // Reconfigure off the UI thread.
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.reconfigureWakeWord()
            }
            // L4: the URI is only read once (during the copy above), so the
            // persistable permission can be released immediately.
            contentResolver.releasePersistableUriPermission(uri, takeFlags)
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
        ) {
            CredentialsStore.get().picovoiceKey = picovoiceKey
            CredentialsStore.get().saluteClientId = saluteId
            CredentialsStore.get().saluteClientSecret = saluteSecret
            CredentialsStore.get().gigaChatClientId = gigaChatId
            CredentialsStore.get().gigaChatClientSecret = gigaChatSecret
            // Force a token refresh so a changed Picovoice/Sber key applies now.
            GraphHolder.graph?.tokenManager?.invalidate()
            // Apply a changed Picovoice key live to the wake-word engine (this
            // method is suspend, so reconfigure can be awaited directly).
            GraphHolder.graph?.reconfigureWakeWord()
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

    /** No-op stand-in until onCreate replaces it with [RealCallbacks]. */
    private object StubCallbacks : SettingsCallbacks {
        override suspend fun onSaveCredentials(
            picovoiceKey: String,
            saluteId: String,
            saluteSecret: String,
            gigaChatId: String,
            gigaChatSecret: String,
        ) {
        }

        override fun onLlmProviderSelected(type: String) {}

        override suspend fun onSaveLlmProviderSettings(baseUrl: String, model: String, apiKey: String) {}

        override fun onWakeWordSelected(modelId: String) {}

        override fun onLoadCustomPpn() {}

        override fun onEngineSelected(engine: String) {}

        override fun onSensitivityChanged(value: Float) {}
    }

    private companion object {
        const val PPN_REQUEST = 1002
        const val CAPTURE_REQUEST = 1003
    }
}
