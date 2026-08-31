package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import kotlinx.coroutines.Dispatchers
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

    private lateinit var wakeWordGroup: RadioGroup
    private lateinit var sensitivityBar: SeekBar
    private lateinit var sensitivityValue: TextView

    private lateinit var llmProviderGroup: RadioGroup
    private lateinit var openAiBlock: View
    private lateinit var openAiBaseUrl: TextInputEditText
    private lateinit var openAiModel: TextInputEditText
    private lateinit var openAiApiKey: TextInputEditText

    private lateinit var engineGroup: RadioGroup
    private lateinit var porcupineBlock: View
    private lateinit var sherpaBlock: View

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
        picovoiceKey.setText(CredentialsStore.picovoiceKey)
        saluteId.setText(CredentialsStore.saluteClientId)
        saluteSecret.setText(CredentialsStore.saluteClientSecret)
        gigaChatId.setText(CredentialsStore.gigaChatClientId)
        gigaChatSecret.setText(CredentialsStore.gigaChatClientSecret)

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

        callbacks = RealCallbacks()

        // Close / back affordance (theme is NoActionBar).
        findViewById<View>(R.id.closeButton).setOnClickListener { finish() }

        // A) Save provider credentials.
        findViewById<Button>(R.id.saveCredentialsButton).setOnClickListener {
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

    /** Show the controls for the active engine, hide the other. */
    private fun applyEngineVisibility(engine: String) {
        val isSherpa = engine == "sherpa"
        porcupineBlock.visibility = if (isSherpa) View.GONE else View.VISIBLE
        sherpaBlock.visibility = if (isSherpa) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PPN_REQUEST && resultCode == RESULT_OK && data != null) {
            val uri = data.data ?: return
            // L2: only a .ppn file is valid.
            if (uri.lastPathSegment?.endsWith(".ppn", ignoreCase = true) != true) {
                Toast.makeText(
                    this,
                    "Неверный файл wake word (нужен .ppn)",
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
            CredentialsStore.picovoiceKey = picovoiceKey
            CredentialsStore.saluteClientId = saluteId
            CredentialsStore.saluteClientSecret = saluteSecret
            CredentialsStore.gigaChatClientId = gigaChatId
            CredentialsStore.gigaChatClientSecret = gigaChatSecret
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
    }
}
