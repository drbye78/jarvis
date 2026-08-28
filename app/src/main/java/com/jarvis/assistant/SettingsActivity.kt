package com.jarvis.assistant

import android.content.Context
import android.content.Intent
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
 * [CredentialsStore] and the live wake-word [com.jarvis.assistant.audio.PorcupineDetector]
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

    /** The chosen wake-word model changed (`builtin` | `custom_bundled`). */
    fun onWakeWordSelected(modelId: String)

    /** User asked to load a custom .ppn file from the device. */
    fun onLoadCustomPpn()

    /** Porcupine sensitivity changed, range 0.0–1.0. */
    fun onSensitivityChanged(value: Float)
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

        // B) Sensitivity slider: SeekBar 0..100 → Porcupine 0.0..1.0.
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

    /** Maps a wake-word model id to the path Porcupine should load (null = built-in JARVIS). */
    private fun keywordPathFor(modelId: String): String? = when (modelId) {
        "builtin" -> null
        "custom_user" -> appPrefs.customWakeWordPath.ifBlank { "jarvis_ru.ppn" }
        else -> "jarvis_ru.ppn" // custom_bundled (default)
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
                GraphHolder.graph?.wakeWordDetector
                    ?.reconfigure(dst.absolutePath, appPrefs.wakeSensitivity)
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
            // Apply a changed Picovoice key live to the wake-word engine
            // (this method is suspend, so reconfigure can be awaited directly).
            GraphHolder.graph?.wakeWordDetector
                ?.reconfigure(keywordPathFor(appPrefs.wakeWordModel), appPrefs.wakeSensitivity)
        }

        override fun onWakeWordSelected(modelId: String) {
            appPrefs.wakeWordModel = modelId
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.wakeWordDetector
                    ?.reconfigure(keywordPathFor(modelId), appPrefs.wakeSensitivity)
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

        override fun onSensitivityChanged(value: Float) {
            appPrefs.wakeSensitivity = value
            lifecycleScope.launch(Dispatchers.Default) {
                GraphHolder.graph?.wakeWordDetector
                    ?.reconfigure(keywordPathFor(appPrefs.wakeWordModel), value)
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

        override fun onWakeWordSelected(modelId: String) {}

        override fun onLoadCustomPpn() {}

        override fun onSensitivityChanged(value: Float) {}
    }

    private companion object {
        const val PPN_REQUEST = 1002
    }
}
