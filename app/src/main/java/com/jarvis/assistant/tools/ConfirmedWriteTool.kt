package com.jarvis.assistant.tools

/**
 * Implemented by an external (MCP) tool whose risk is
 * [ToolRisk.EXTERNAL_WRITE] so the registry can derive the confirmation key
 * identity for it.
 *
 * This is now a thin, MCP-shaped specialisation of the domain-neutral
 * [ConfirmedTool]: `confirmationDomain` = the configured server id,
 * `confirmationAction` = the server's ORIGINAL tool name (not the mangled
 * namespaced one). Deliberately NO MCP imports: this is a pure seam the MCP
 * adapter implements. The pure confirmation core ([WriteBinding] +
 * [WriteConfirmation]) stays Android- and MCP-free.
 */
interface ConfirmedWriteTool : ConfirmedTool {
    /** The configured MCP server id this tool belongs to. */
    val confirmationServerId: String

    /** The server's original, un-namespaced tool name. */
    val confirmationToolName: String

    override val confirmationDomain: String get() = confirmationServerId
    override val confirmationAction: String get() = confirmationToolName
}
