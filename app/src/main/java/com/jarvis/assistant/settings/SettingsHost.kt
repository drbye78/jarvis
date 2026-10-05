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

    /**
     * Launch the R13 §14.6 config-export flow: collect a passphrase, pick a
     * destination through SAF (`CreateDocument`) and write the ALWAYS-encrypted
     * envelope. [includeSecrets] opts the stored secrets into that same
     * encrypted container; there is deliberately no plaintext path.
     */
    fun exportConfig(includeSecrets: Boolean)

    /**
     * Launch the R13 §14.6 config-import flow: collect a passphrase, pick an
     * artifact through SAF (`OpenDocument`), then validate-and-apply it through
     * the graph's `ManagementCore` and report the honest result.
     */
    fun importConfig()

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

    /** Open the MCP server list/edit screen (MCP's in-app navigation). */
    fun openMcpServers()

    /** Open the smart-home connection list/edit screen (HOME's in-app navigation). */
    fun openHomeProviders()

    /** Open the discovered-device browser (aliases + notice curation). */
    fun openHomeDevices()

    /** Open the reversible-action (T1) grants editor. */
    fun openHomeGrants()

    /** Close the current detail screen. */
    fun finishScreen()
}
