package com.jarvis.assistant

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.JarvisForegroundService

class MainActivity : Activity() {

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)
        val toggleButton = findViewById<Button>(R.id.toggleButton)
        val permissionsText = findViewById<TextView>(R.id.permissionsText)

        statusText.text = "Статус: Остановлен"
        toggleButton.text = "Запустить"
        permissionsText.text = getPermissionsStatus()

        toggleButton.setOnClickListener {
            isRunning = !isRunning
            if (isRunning) {
                statusText.text = "Статус: Запущен"
                toggleButton.text = "Остановить"
                val intent = Intent(this, JarvisForegroundService::class.java)
                ContextCompat.startForegroundService(this, intent)
            } else {
                statusText.text = "Статус: Остановлен"
                toggleButton.text = "Запустить"
                val intent = Intent(this, JarvisForegroundService::class.java)
                stopService(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.permissionsText).text = getPermissionsStatus()
    }

    private fun getPermissionsStatus(): String {
        val sb = StringBuilder()

        // 1. RECORD_AUDIO
        val audioGranted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        sb.appendLine("RECORD_AUDIO: ${if (audioGranted) "✓ Granted" else "✗ Not granted"}")

        // 2. Notification Listener
        val listeners = Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        ) ?: ""
        val hasNotificationListener = listeners.contains(packageName)
        sb.appendLine("Notification Listener: ${if (hasNotificationListener) "✓ Enabled" else "✗ Not enabled"}")

        // 3. Battery optimization
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }
        sb.append("Battery optimization: ${if (ignoringBattery) "✓ Ignored" else "✗ Not ignored"}")

        return sb.toString()
    }
}