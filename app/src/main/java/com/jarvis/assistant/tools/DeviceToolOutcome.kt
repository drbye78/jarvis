package com.jarvis.assistant.tools

import android.provider.Settings
import com.jarvis.assistant.util.JsonOut

/**
 * Honesty seam for the device-control tools ([DeviceTools]): a tool builds one
 * of these instead of asserting `"status" to "ok"` by hand, so success is only
 * reported when the change was actually applied.
 *
 * The emitted JSON is deliberately identical to what the tools produced before
 * this seam existed: [Ok] adds the tool-specific fields after `status: ok`,
 * [PanelOpened] uses the `panel_opened` shape the Wi-Fi/Bluetooth tools already
 * emit, and [Unavailable] uses the standard `{"error": …}` object. This keeps
 * the public shape of already-correct tools unchanged.
 */
sealed interface DeviceToolOutcome {

    /** A device change that was verified to have taken effect. */
    data class Ok(val fields: List<Pair<String, Any?>> = emptyList()) : DeviceToolOutcome

    /** A system settings panel was opened; the user (not the app) owns the change. */
    data class PanelOpened(val detail: String) : DeviceToolOutcome

    /** The change could not be made; [reason] is the honest, user-facing explanation. */
    data class Unavailable(val reason: String) : DeviceToolOutcome

    fun toJson(): String = when (this) {
        is Ok -> JsonOut.obj(listOf<Pair<String, Any?>>("status" to "ok") + fields)
        is PanelOpened -> JsonOut.obj("status" to "panel_opened", "detail" to detail)
        is Unavailable -> JsonOut.error(reason)
    }
}

/**
 * Android 15 (API 35) stops apps that target SDK 35 from changing the GLOBAL
 * Do-Not-Disturb state: `setInterruptionFilter` becomes a silent no-op. Below
 * 35 the direct call is still allowed and verified through
 * `currentInterruptionFilter`; at 35+ the tool opens the notification-policy
 * settings screen instead. Pure policy (no Android runtime calls) so the JVM
 * suite can pin both branches — same shape as [ExactAlarmPolicy].
 */
object DndPanelPolicy {

    /** First SDK level where apps may no longer set the global DND filter. */
    const val PANEL_SDK = 35

    fun usePanel(sdkInt: Int): Boolean = sdkInt >= PANEL_SDK

    /** Public SDK action for the notification-policy / DND access settings. */
    val settingsAction: String = Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
}
