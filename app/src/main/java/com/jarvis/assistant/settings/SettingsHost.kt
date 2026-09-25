package com.jarvis.assistant.settings

import androidx.annotation.StringRes
import com.jarvis.assistant.di.AppGraph
import com.jarvis.assistant.ui.FieldValidation

/**
 * The ONLY Activity surface a [SettingsController] may use (settings redesign
 * foundation).
 *
 * Deliberately narrow: a controller binds its own root view and reaches the
 * outside world through these calls. It must NOT call `findViewById` outside
 * its own root (id scoping stays local) and must NOT start an Intent or touch
 * a permission API directly — the host owns those, so navigation, results and
 * lifecycle stay in one place as the screen is split.
 */
interface SettingsHost {
    /**
     * The assistant graph once the service has finished building it, or null
     * when the assistant is not running. Suspends until readiness rather than
     * polling `GraphHolder`.
     */
    suspend fun awaitAssistantGraph(): AppGraph?

    /** Ask for the weather-location permission (host owns the request flow). */
    fun requestWeatherLocationPermission()

    /** Launch the custom `.ppn` file picker. */
    fun importCustomPpn()

    /** Ask for the playback-capture grant (AEC lane). */
    fun requestPlaybackCapture()

    /** Open an external URL (e.g. a credential help page) in the browser. */
    fun openUrl(url: String)

    /** Show a short toast from a string resource. */
    fun toast(@StringRes res: Int)

    /** Attach validation errors to the fields that own them. */
    fun renderFieldErrors(errors: List<FieldValidation.FieldError>)

    /** Record that [policy]'s change is pending a restart. */
    fun markPending(policy: ApplyPolicy)

    /** Navigate to another category's detail screen (BRAIN's credential tap-through). */
    fun openCategory(category: SettingsCategory)

    /** Open the memory inspector screen (MEMORY's in-app navigation). */
    fun openMemoryInspector()

    /** Close the current detail screen. */
    fun finishScreen()
}
