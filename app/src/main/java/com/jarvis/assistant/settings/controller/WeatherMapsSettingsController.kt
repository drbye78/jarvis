package com.jarvis.assistant.settings.controller

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.jarvis.assistant.R
import com.jarvis.assistant.settings.ApplyPolicy
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.settings.SettingsLinks
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore

/**
 * WEATHER_MAPS («Погода и карты») detail screen controller (settings redesign).
 *
 * Ports the old Activity's `setupWeatherCard` / `saveWeatherLocation` /
 * `refreshWeatherPermissionStatus` / `hasLocationPermission` / `setupMapsCard` /
 * `commitMapKitKey` / `openExternalUrl` behaviour onto the new
 * `screen_settings_weather_maps.xml`.
 *
 * TWO settings, two levels:
 *  - `weatherLocation` is ESSENTIAL and LIVE: the configured city is the
 *    primary path on GMS-free, WiFi-only devices and the pref is read per
 *    weather turn.
 *  - `mapKitApiKey` is ADVANCED and [ApplyPolicy.APP_RESTART]: MapKit may
 *    `setApiKey` only ONCE per process, so even a service restart is not
 *    enough. Committing the key marks the pending change so the host banner
 *    tells the user a full app restart is required.
 *
 * The Yandex Maps attribution block (text + terms link + open-maps action) is
 * ESSENTIAL and always visible: it is the licensing mitigation, not a setting,
 * so it deliberately sits OUTSIDE the advanced container.
 *
 * The configured city WINS over GPS — this controller never promises a GPS
 * override, and [SettingsMapping.weatherPermissionStatus] hides the grant row
 * entirely once a city is configured.
 */
class WeatherMapsSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var context: Context

    private lateinit var weatherLocationInput: TextInputEditText
    private lateinit var weatherLocationPermissionStatus: TextView

    private lateinit var mapKitApiKey: TextInputEditText

    private lateinit var advancedContainer: View

    override fun bind(root: View) {
        context = root.context
        weatherLocationInput = root.findViewById(R.id.weatherLocationInput)
        weatherLocationPermissionStatus = root.findViewById(R.id.weatherLocationPermissionStatus)
        mapKitApiKey = root.findViewById(R.id.mapKitApiKey)
        advancedContainer = root.findViewById(R.id.settingsWeatherMapsAdvanced)

        bindWeatherCard(root)
        bindMapsCard(root)
        bindDisclosure(root)

        refreshWeatherPermissionStatus()
    }

    override fun onResume() {
        // Guard against a host that resumes before binding; the frozen seam has
        // no ordering guarantee beyond "bind, then lifecycle". The grant can
        // change while this screen is away (the system permission dialog).
        if (!::weatherLocationPermissionStatus.isInitialized) return
        refreshWeatherPermissionStatus()
    }

    /**
     * Weather card: default city (primary path) + the location-permission
     * affordance. The configured city is read live from the pref.
     */
    private fun bindWeatherCard(root: View) {
        weatherLocationInput.setText(prefs.weatherLocation)

        // Commit on the explicit Save button AND on IME-done / focus loss — the
        // same free-text commit pattern the old card used, so editing and
        // tapping away does not silently lose the edit.
        weatherLocationInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                saveWeatherLocation()
                true
            } else {
                false
            }
        }
        weatherLocationInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveWeatherLocation()
        }

        root.findViewById<Button>(R.id.weatherLocationSaveButton).setOnClickListener {
            saveWeatherLocation()
        }
        root.findViewById<Button>(R.id.weatherLocationPermissionButton).setOnClickListener {
            host.requestWeatherLocationPermission()
        }
    }

    /**
     * Persist the current city through the callback layer; blank means
     * "auto-detect", normalized by [SettingsMapping.weatherLocationOrDefault].
     * The permission row is re-read afterwards because a city change can flip
     * it between DENIED and NOT_NEEDED.
     */
    private fun saveWeatherLocation() {
        callbacks.onWeatherLocationSaved(
            SettingsMapping.weatherLocationOrDefault(weatherLocationInput.text.toString()),
        )
        refreshWeatherPermissionStatus()
    }

    /**
     * Re-read the live grant and render the status row. A configured city makes
     * the GPS path irrelevant, so the row is hidden in that case (the request
     * button stays available for when the city is cleared).
     */
    private fun refreshWeatherPermissionStatus() {
        val status = SettingsMapping.weatherPermissionStatus(
            fineGranted = hasLocationPermission(Manifest.permission.ACCESS_FINE_LOCATION),
            coarseGranted = hasLocationPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            configured = prefs.weatherLocation,
        )
        when (status) {
            SettingsMapping.WeatherPermissionStatus.GRANTED -> {
                weatherLocationPermissionStatus.visibility = View.VISIBLE
                weatherLocationPermissionStatus.setText(R.string.weather_permission_granted)
            }
            SettingsMapping.WeatherPermissionStatus.DENIED -> {
                weatherLocationPermissionStatus.visibility = View.VISIBLE
                weatherLocationPermissionStatus.setText(R.string.weather_permission_denied)
            }
            SettingsMapping.WeatherPermissionStatus.NOT_NEEDED -> {
                weatherLocationPermissionStatus.visibility = View.GONE
            }
        }
    }

    private fun hasLocationPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Maps card: the process-scoped MapKit key + the always-visible attribution block. */
    private fun bindMapsCard(root: View) {
        // The key is committed on the explicit Save button and, like the other
        // free-text fields, on IME-done / focus loss. It is OPTIONAL and
        // validated at use time, so a blank value is stored as-is.
        mapKitApiKey.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitMapKitKey()
                true
            } else {
                false
            }
        }
        mapKitApiKey.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitMapKitKey()
        }
        root.findViewById<Button>(R.id.mapKitApiKeySaveButton).setOnClickListener {
            commitMapKitKey()
            host.toast(R.string.maps_saved)
        }

        // Attribution block — always on screen (essential).
        root.findViewById<Button>(R.id.mapKitTermsButton).setOnClickListener {
            host.openUrl(SettingsLinks.YANDEX_MAPS_TERMS_URL)
        }
        root.findViewById<Button>(R.id.mapKitOpenMapsButton).setOnClickListener {
            host.openUrl(SettingsLinks.YANDEX_MAPS_URL)
        }
    }

    /**
     * Persist the MapKit key to the vault slot the geo lane reads (blank = not
     * configured) and record the pending full-app restart. Marking here (not
     * only on the Save button) means an IME-done / focus-loss commit is also
     * reported, matching the fact that the stored key already changed.
     */
    private fun commitMapKitKey() {
        CredentialsStore.get().mapKitApiKey = mapKitApiKey.text.toString().trim()
        host.markPending(ApplyPolicy.APP_RESTART)
    }

    /**
     * The disclosure row exists because the screen has both essential and
     * advanced content; the only advanced entry is the MapKit key.
     */
    private fun bindDisclosure(root: View) {
        val toggle = root.findViewById<View>(R.id.settingsAdvancedToggle)
        val label = root.findViewById<TextView>(R.id.settingsAdvancedLabel)
        val chevron = root.findViewById<View>(R.id.settingsAdvancedChevron)
        toggle.visibility = View.VISIBLE
        label.text = context.getString(R.string.settings_advanced_show, ADVANCED_COUNT)
        toggle.setOnClickListener {
            val expanded = advancedContainer.visibility != View.VISIBLE
            advancedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
            chevron.rotation = if (expanded) 180f else 0f
        }
    }

    private companion object {
        /** Weather & maps advanced contract-table entries: only `mapKitApiKey`. */
        const val ADVANCED_COUNT = 1
    }
}
