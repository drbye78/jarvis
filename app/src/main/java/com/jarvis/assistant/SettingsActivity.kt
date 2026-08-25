package com.jarvis.assistant

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.config.ProviderSettings
import com.jarvis.assistant.di.GraphHolder
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.util.AppPrefs

/**
 * Settings: LLM provider selection (GigaChat / any OpenAI-compatible
 * endpoint), wake-word sensitivity. Changes take effect after the service
 * restart, which the Apply button performs.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private lateinit var providerSpinner: Spinner
    private lateinit var baseUrlField: EditText
    private lateinit var apiKeyField: EditText
    private lateinit var modelField: EditText
    private lateinit var sensitivityBar: SeekBar
    private lateinit var sensitivityLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        prefs = AppPrefs(this)

        providerSpinner = findViewById(R.id.providerSpinner)
        baseUrlField = findViewById(R.id.baseUrlField)
        apiKeyField = findViewById(R.id.apiKeyField)
        modelField = findViewById(R.id.modelField)
        sensitivityBar = findViewById(R.id.sensitivityBar)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)

        val options = listOf("GigaChat (Сбер)", "OpenAI-совместимый API")
        providerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        providerSpinner.setSelection(
            if (prefs.providerType == ProviderSettings.Type.OPENAI_COMPAT) 1 else 0
        )
        providerSpinner.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long,
            ) {
                val openai = pos == 1
                baseUrlField.isEnabled = openai
                apiKeyField.isEnabled = openai
                modelField.isEnabled = openai
            }

            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        baseUrlField.setText(prefs.openAiBaseUrl)
        apiKeyField.setText(prefs.openAiApiKey)
        modelField.setText(prefs.openAiModel)

        sensitivityBar.max = 100
        sensitivityBar.progress = (prefs.wakeSensitivity * 100).toInt()
        sensitivityLabel.text = getString(R.string.sensitivity_value, prefs.wakeSensitivity)
        sensitivityBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                val v = (value / 100f)
                sensitivityLabel.text = getString(R.string.sensitivity_value, v)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        findViewById<Button>(R.id.applyButton).setOnClickListener { applyAndRestart() }
    }

    private fun applyAndRestart() {
        prefs.providerType =
            if (providerSpinner.selectedItemPosition == 1) ProviderSettings.Type.OPENAI_COMPAT
            else ProviderSettings.Type.GIGACHAT
        prefs.openAiBaseUrl = baseUrlField.text.toString().trim()
        prefs.openAiApiKey = apiKeyField.text.toString().trim()
        prefs.openAiModel = modelField.text.toString().trim()
        prefs.wakeSensitivity = sensitivityBar.progress / 100f

        if (prefs.providerType == ProviderSettings.Type.OPENAI_COMPAT) {
            if (prefs.openAiBaseUrl.isBlank()) {
                Toast.makeText(this, R.string.error_base_url, Toast.LENGTH_SHORT).show()
                return
            }
            if (prefs.openAiApiKey.isBlank()) {
                Toast.makeText(this, R.string.error_api_key, Toast.LENGTH_SHORT).show()
                return
            }
        }

        // Provider settings are baked into the graph at construction, so
        // restart the service to rebuild everything.
        if (GraphHolder.isRunning) {
            JarvisForegroundService.explicitStop(this)
        }
        JarvisForegroundService.explicitStart(this)
        Toast.makeText(this, R.string.settings_applied, Toast.LENGTH_SHORT).show()
        finish()
    }
}
