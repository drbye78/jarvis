package com.jarvis.assistant.tools

/**
 * Implemented by an external (MCP) tool whose risk is
 * [ToolRisk.EXTERNAL_WRITE] so the registry can derive the confirmation key
 * identity for it.
 *
 * Deliberately NO MCP imports: this is a pure seam the MCP adapter implements
 * in a later lane (`confirmationServerId` = the configured server id,
 * `confirmationToolName` = the server's ORIGINAL tool name, not the mangled
 * namespaced one). The pure confirmation core ([WriteBinding] +
 * [WriteConfirmation]) must stay Android- and MCP-free.
 */
interface ConfirmedWriteTool {
    /** The configured MCP server id this tool belongs to. */
    val confirmationServerId: String

    /** The server's original, un-namespaced tool name. */
    val confirmationToolName: String
}
