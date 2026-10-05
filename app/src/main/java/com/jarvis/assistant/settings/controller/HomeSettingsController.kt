package com.jarvis.assistant.settings.controller

import android.view.View
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.assistant.R
import com.jarvis.assistant.home.HomeConfigCodec
import com.jarvis.assistant.home.HomeConfigDecodeResult
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.util.AppPrefs

/**
 * «Умный дом» / smart-home detail screen controller (R4).
 *
 * The screen is one info block, a row that opens [SettingsHost.openHomeProviders]
 * (the connection list/edit Activity) and the proactive-awareness toggle.
 * Parsing/validation/persistence of the provider list live in
 * [HomeConfigCodec] and [com.jarvis.assistant.home.providers.ha] — this
 * controller holds no home state and never touches the vault.
 *
 * `homeProviders` is sealed at service start (the process-scoped HA backend
 * reads it then), so the connection row shows the restart hint. The awareness
 * toggle ([AppPrefs.homeAwarenessEnabled]) is LIVE — it is read per notice.
 */
class HomeSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var summary: TextView
    private lateinit var awareness: MaterialSwitch

    override fun bind(root: View) {
        summary = root.findViewById(R.id.homeProvidersSummary)
        root.findViewById<View>(R.id.homeProvidersRow).setOnClickListener {
            host.openHomeProviders()
        }
        root.findViewById<View>(R.id.homeDevicesRow).setOnClickListener {
            host.openHomeDevices()
        }
        root.findViewById<View>(R.id.homeGrantsRow).setOnClickListener {
            host.openHomeGrants()
        }

        awareness = root.findViewById(R.id.homeAwarenessSwitch)
        awareness.isChecked = prefs.homeAwarenessEnabled
        awareness.setOnCheckedChangeListener { _, checked ->
            prefs.homeAwarenessEnabled = checked
            // LIVE: no pending-restart mark (see ApplyPolicies).
        }

        renderSummary()
    }

    override fun onResume() {
        // The connection list may have changed in the editor Activity; re-read
        // it here rather than caching (the seam's contract re-reads on resume).
        if (::summary.isInitialized) renderSummary()
        if (::awareness.isInitialized) awareness.isChecked = prefs.homeAwarenessEnabled
    }

    /** One-line summary of the persisted provider list (never a secret). */
    private fun renderSummary() {
        summary.text = when (val decoded = HomeConfigCodec.decode(prefs.homeProviders)) {
            HomeConfigDecodeResult.Empty -> context().getString(R.string.settings_home_none_configured)
            is HomeConfigDecodeResult.Invalid -> context().getString(R.string.settings_home_provider_list_invalid)
            is HomeConfigDecodeResult.Ok -> {
                if (decoded.configs.isEmpty()) {
                    context().getString(R.string.settings_home_none_configured)
                } else {
                    context().getString(R.string.settings_home_configured_count, decoded.configs.size)
                }
            }
        }
    }

    private fun context() = summary.context
}
