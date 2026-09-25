package com.jarvis.assistant.settings.controller

import android.view.View
import android.widget.Button
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.R
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import java.util.Locale

/**
 * PROACTIVITY («Инициатива») detail screen controller (settings redesign).
 *
 * Ports the old Activity's `setupBehaviorCard` behaviour onto the new
 * `screen_settings_proactivity.xml`.
 *
 * `behaviorEnabled` is ESSENTIAL and LIVE: the coordinator consumes it through
 * `PrefsFlow` StateFlows, so a toggle applies without a restart. The quiet-hour
 * and daily-quota steppers are ADVANCED and LIVE.
 *
 * The wrap-around (quiet hours) and clamp (quota 1..5) arithmetic lives in
 * [SettingsMapping.quietHour] / [SettingsMapping.quota]; this controller only
 * applies the returned value and re-renders the labels.
 */
class ProactivitySettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var enabledSwitch: MaterialSwitch
    private lateinit var quietStartValue: TextView
    private lateinit var quietEndValue: TextView
    private lateinit var quotaValue: TextView

    override fun bind(root: View) {
        enabledSwitch = root.findViewById(R.id.behaviorEnabledSwitch)
        enabledSwitch.isChecked = prefs.behaviorEnabled
        enabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.behaviorEnabled = checked
        }

        quietStartValue = root.findViewById(R.id.behaviorQuietStartValue)
        quietEndValue = root.findViewById(R.id.behaviorQuietEndValue)
        quotaValue = root.findViewById(R.id.behaviorQuotaValue)

        root.findViewById<Button>(R.id.behaviorQuietStartMinus).setOnClickListener {
            prefs.behaviorQuietStart = SettingsMapping.quietHour(prefs.behaviorQuietStart, -1)
            renderValues()
        }
        root.findViewById<Button>(R.id.behaviorQuietStartPlus).setOnClickListener {
            prefs.behaviorQuietStart = SettingsMapping.quietHour(prefs.behaviorQuietStart, 1)
            renderValues()
        }
        root.findViewById<Button>(R.id.behaviorQuietEndMinus).setOnClickListener {
            prefs.behaviorQuietEnd = SettingsMapping.quietHour(prefs.behaviorQuietEnd, -1)
            renderValues()
        }
        root.findViewById<Button>(R.id.behaviorQuietEndPlus).setOnClickListener {
            prefs.behaviorQuietEnd = SettingsMapping.quietHour(prefs.behaviorQuietEnd, 1)
            renderValues()
        }
        root.findViewById<Button>(R.id.behaviorQuotaMinus).setOnClickListener {
            prefs.behaviorDailyQuota = SettingsMapping.quota(prefs.behaviorDailyQuota, -1)
            renderValues()
        }
        root.findViewById<Button>(R.id.behaviorQuotaPlus).setOnClickListener {
            prefs.behaviorDailyQuota = SettingsMapping.quota(prefs.behaviorDailyQuota, 1)
            renderValues()
        }

        renderValues()
        bindDisclosure(root)
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle". Re-read the
        // reactive prefs so a value changed elsewhere is reflected here.
        if (!::enabledSwitch.isInitialized) return
        enabledSwitch.isChecked = prefs.behaviorEnabled
        renderValues()
    }

    /** Render the three advanced values from the prefs (single source of truth). */
    private fun renderValues() {
        quietStartValue.text = String.format(Locale.US, "%02d:00", prefs.behaviorQuietStart)
        quietEndValue.text = String.format(Locale.US, "%02d:00", prefs.behaviorQuietEnd)
        quotaValue.text = prefs.behaviorDailyQuota.toString()
    }

    /**
     * The disclosure row exists because the screen has both essential and
     * advanced content; its three advanced entries are the quiet-start hour,
     * the quiet-end hour and the daily quota.
     */
    private fun bindDisclosure(root: View) {
        val toggle = root.findViewById<View>(R.id.settingsAdvancedToggle)
        val label = root.findViewById<TextView>(R.id.settingsAdvancedLabel)
        val chevron = root.findViewById<View>(R.id.settingsAdvancedChevron)
        val advanced = root.findViewById<View>(R.id.settingsProactivityAdvanced)
        toggle.visibility = View.VISIBLE
        label.text = label.context.getString(R.string.settings_advanced_show, ADVANCED_COUNT)
        toggle.setOnClickListener {
            val expanded = advanced.visibility != View.VISIBLE
            advanced.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 180f else 0f
        }
    }

    private companion object {
        /** Proactivity advanced contract-table entries: quiet start/end + quota. */
        const val ADVANCED_COUNT = 3
    }
}
