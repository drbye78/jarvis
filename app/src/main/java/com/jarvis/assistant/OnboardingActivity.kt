package com.jarvis.assistant

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.tools.JarvisDeviceAdmin
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore

/**
 * First-run onboarding: every permission and access the assistant needs as
 * a STATUS ROW — status icon (done / pending), label + weight caption
 * (обязательно / опционально / для музыки / для речи), and the action
 * control that grants it. Rows are built in code from one declarative list
 * (the old screen rendered a monospace ✓/✗ wall next to six unlabelled
 * buttons; adding a row is now one [PermRow] entry).
 *
 * Start gating: «Запустить Джарвиса» enables only when the mandatory rows
 * (микрофон, оптимизация батареи) are green; the gate hint says exactly
 * that. Rows that route through external Settings screens refresh in
 * [onResume] — the user returns and sees new state immediately.
 *
 * Note on view construction: row controls are plain framework TextViews
 * with ripple backgrounds rather than programmatic MaterialButtons —
 * MaterialButton cannot take a style after construction, and unstyled
 * programmatic instances ignore the theme. TextViews are theme-safe and
 * visually identical to TextButtons at this size.
 */
class OnboardingActivity : AppCompatActivity() {

    /** One onboarding row: label, weight caption, status check, grant action. */
    private data class PermRow(
        val label: String,
        val caption: String,
        val mandatory: Boolean = false,
        val check: () -> Boolean,
        val action: () -> Unit,
    )

    private lateinit var rowsContainer: LinearLayout
    private lateinit var startButton: com.google.android.material.button.MaterialButton
    private lateinit var gateHint: TextView

    private var rows: List<PermRow> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        rowsContainer = findViewById(R.id.permRows)
        startButton = findViewById(R.id.btnStart)
        gateHint = findViewById(R.id.startGateHint)

        startButton.setOnClickListener {
            AppPrefs(this).onboarded = true
            JarvisForegroundService.explicitStart(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        buildRows()
    }

    private fun buildRows() {
        rows = listOf(
            PermRow(
                getString(R.string.onboarding_row_mic),
                getString(R.string.onboarding_weight_required),
                mandatory = true,
                check = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                },
                action = {
                    ActivityCompat.requestPermissions(
                        this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC,
                    )
                },
            ),
            PermRow(
                getString(R.string.onboarding_row_notif),
                getString(R.string.onboarding_weight_music),
                check = { notificationListenerEnabled() },
                action = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
            ),
            PermRow(
                getString(R.string.onboarding_row_battery),
                getString(R.string.onboarding_weight_required),
                mandatory = true,
                check = {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                        pm.isIgnoringBatteryOptimizations(packageName)
                },
                action = { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
            ),
            PermRow(
                getString(R.string.onboarding_row_write),
                getString(R.string.onboarding_weight_optional),
                check = { Settings.System.canWrite(this) },
                action = { startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)) },
            ),
            PermRow(
                getString(R.string.onboarding_row_dnd),
                getString(R.string.onboarding_weight_optional),
                check = {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    nm.isNotificationPolicyAccessGranted
                },
                action = { startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) },
            ),
            PermRow(
                getString(R.string.onboarding_row_admin),
                getString(R.string.onboarding_weight_optional),
                check = {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    dpm.isAdminActive(ComponentName(this, JarvisDeviceAdmin::class.java))
                },
                action = { startActivity(addDeviceAdminIntent()) },
            ),
            PermRow(
                getString(R.string.onboarding_row_keys),
                getString(R.string.onboarding_weight_speech),
                check = { CredentialsStore.hasRequiredSber() },
                action = { startActivity(Intent(this, SettingsActivity::class.java)) },
            ),
        )
    }

    private fun addDeviceAdminIntent(): Intent =
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

    /** enabled_notification_listeners holds flattened ComponentNames. */
    private fun notificationListenerEnabled(): Boolean {
        val listeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: ""
        return listeners.contains(packageName)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    /** Rebuilds the rows with fresh statuses + re-evaluates the start gate. */
    private fun refreshStatus() {
        rowsContainer.removeAllViews()
        val mandatoryDone = rows.filter { it.mandatory }.all { it.check() }
        rows.forEach { row -> rowsContainer.addView(rowView(row, row.check())) }

        startButton.isEnabled = mandatoryDone
        gateHint.visibility = if (mandatoryDone) View.GONE else View.VISIBLE
        if (!mandatoryDone) gateHint.text = getString(R.string.onboarding_gate_hint)
    }

    /** One row: [status icon] [label + caption] [action control / «готово»]. */
    private fun rowView(row: PermRow, done: Boolean): View {
        val rowLayout = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tool_line)
            setPadding(dp(14), dp(12), dp(12), dp(12))
        }

        rowLayout.addView(ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(14) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (done) {
                setImageResource(R.drawable.ic_check_circle)
                setColorFilter(color(R.color.jarvis_status_listening))
            } else {
                setImageResource(R.drawable.ic_radio_unchecked)
                setColorFilter(color(R.color.jarvis_outline))
            }
        })

        rowLayout.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@OnboardingActivity).apply {
                text = row.label
                setTextColor(color(R.color.jarvis_on_surface))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            addView(TextView(this@OnboardingActivity).apply {
                text = if (done) getString(R.string.onboarding_done) else row.caption
                setTextColor(color(R.color.jarvis_on_surface_variant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            })
        })

        if (done) {
            rowLayout.addView(TextView(this).apply {
                text = getString(R.string.onboarding_done)
                setTextColor(color(R.color.jarvis_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
        } else {
            rowLayout.addView(TextView(this).apply {
                text = rowActionLabel(row)
                setTextColor(color(R.color.jarvis_primary))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val out = TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { row.action() }
            })
        }
        return rowLayout
    }

    /** Compact action label for the pending state. */
    private fun rowActionLabel(row: PermRow): String =
        if (row.label == getString(R.string.onboarding_row_mic)) {
            getString(R.string.onboarding_action_grant)
        } else if (row.label == getString(R.string.onboarding_row_keys)) {
            getString(R.string.onboarding_action_settings)
        } else {
            getString(R.string.onboarding_action_open)
        }

    private fun color(res: Int): Int = ContextCompat.getColor(this, res)

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC) refreshStatus()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQ_MIC = 1
    }
}
