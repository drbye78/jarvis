package com.jarvis.assistant.settings.controller

import android.view.View
import com.jarvis.assistant.R
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.util.AppPrefs

/**
 * «MCP-серверы» / MCP detail screen controller (multi-server MCP lane, P1-D).
 *
 * Deliberately DUMB: the screen is one info block plus a row that opens
 * [SettingsHost.openMcpServers]. All parsing, validation and persistence live
 * in [com.jarvis.assistant.mcp.McpServerConfigCodec] /
 * [com.jarvis.assistant.mcp.McpUrlPolicy] and in the list Activity, so this
 * controller holds no MCP state and never touches the vault directly.
 *
 * The persisted `mcpServers` blob is read LIVE by the catalog, which is why
 * there is no disclosure, no restart hint and no pending banner here.
 */
class McpSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    override fun bind(root: View) {
        root.findViewById<View>(R.id.mcpServersRow).setOnClickListener {
            host.openMcpServers()
        }
    }
}
