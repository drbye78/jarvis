package com.jarvis.assistant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.tools.JarvisDeviceAdmin
import com.jarvis.assistant.util.AppPrefs

/**
 * First-run onboarding: walks the user through every permission and access
 * the assistant needs, showing live status for each. The original app
 * promised "grant RECORD_AUDIO when prompted" but never actually requested
 * it — fresh installs were silently dead. This screen fixes that, plus the
 * optional accesses the device-control tools need (write-settings, DND,
 * device admin).
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        statusView = findViewById(R.id.permStatus)

        findViewById<Button>(R.id.btnGrantMic).setOnClickListener {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC,
            )
        }
        findViewById<Button>(R.id.btnNotifListener).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.btnWriteSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))
        }
        findViewById<Button>(R.id.btnDnd).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        }
        findViewById<Button>(R.id.btnDeviceAdmin).setOnClickListener {
            startActivity(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(
                        DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this@OnboardingActivity, JarvisDeviceAdmin::class.java),
                    )
                    putExtra(
                        DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.admin_explanation),
                    )
                }
            )
        }
        findViewById<Button>(R.id.btnStart).setOnClickListener {
            com.jarvis.assistant.util.AppPrefs(this).onboarded = true
            JarvisForegroundService.explicitStart(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val sb = StringBuilder()

        fun line(ok: Boolean, label: String) {
            sb.append(if (ok) "✓ " else "✗ ").appendLine(label)
        }

        val micGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        line(micGranted, "Микрофон (обязательно)")

        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        line(listeners.contains(packageName), "Доступ к уведомлениям (для приглушения музыки)")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        line(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M || pm.isIgnoringBatteryOptimizations(packageName),
            "Отключена оптимизация батареи (обязательно)",
        )

        line(Settings.System.canWrite(this), "Изменение системных настроек (яркость — опционально)")

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        line(nm.isNotificationPolicyAccessGranted, "Режим «Не беспокоить» (опционально)")

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        line(
            dpm.isAdminActive(ComponentName(this, JarvisDeviceAdmin::class.java)),
            "Блокировка экрана (опционально)",
        )

        val modelPresent = assets.list("")?.contains("jarvis_ru.ppn") == true
        line(modelPresent, "Модель wake word (jarvis_ru.ppn)")

        val credentials = com.jarvis.assistant.util.CredentialsStore.hasRequiredSber()
        line(credentials, "Ключи Сбера (укажите в настройках)")

        statusView.text = sb.toString()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) refreshStatus()
    }

    private companion object {
        const val REQ_MIC = 1
    }
}
