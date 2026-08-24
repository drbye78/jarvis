package com.jarvis.assistant.tools

import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.ToolContract
import com.jarvis.assistant.contracts.ToolResult

/**
 * Holds the full set of assistant tools and provides lookup/execution.
 *
 * Per-tool exceptions are caught so the caller always receives a [ToolResult].
 * Unknown tool names produce a clear error result.
 */
class ToolRegistry(
    private val tools: List<ToolContract>
) {

    fun available(): List<ToolContract> = tools

    suspend fun execute(call: FunctionCall): ToolResult {
        val tool = tools.find { it.name == call.name }
        if (tool == null) {
            return ToolResult(call, """{"error":"Unknown function: ${call.name}"}""")
        }
        return try {
            val result = tool.execute(call.arguments)
            ToolResult(call, result)
        } catch (e: Exception) {
            ToolResult(call, """{"error":"Tool execution failed: ${e.message}"}""")
        }
    }
}