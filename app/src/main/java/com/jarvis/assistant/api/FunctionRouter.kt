package com.jarvis.assistant.api

import android.content.Context
import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.Tool as SerializationTool
import com.jarvis.assistant.contracts.ToolContract
import com.jarvis.assistant.contracts.ToolFunction
import com.jarvis.assistant.contracts.ToolResult
import com.jarvis.assistant.tools.ToolRegistry

/**
 * Registry and executor for assistant tools.
 *
 * Decoupled from the data layer: it accepts a [historyProvider] lambda
 * (`() -> List<Message>`) instead of importing a concrete ConversationManager,
 * so it can be wired up once that type exists without a cross-phase dependency.
 *
 * [execute] delegates to [ToolRegistry] and always returns a [ToolResult] —
 * unknown tool names produce a clear error result rather than throwing.
 */
class FunctionRouter(
    @Suppress("unused") context: Context,
    private val historyProvider: suspend () -> List<Message> = { emptyList() }
) {

    private val toolRegistry = ToolRegistry(
        listOf(
            object : ToolContract {
                override val name = "setAlarm"
                override val description = "Set an alarm at a given time."
                override val parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "time": { "type": "string", "description": "ISO-8601 time, e.g. 07:30" },
                        "label": { "type": "string", "description": "Optional alarm label" }
                      },
                      "required": ["time"]
                    }
                """.trimIndent()

                override suspend fun execute(arguments: String): String {
                    return """{"error":"not configured"}"""
                }
            },
            object : ToolContract {
                override val name = "controlDevice"
                override val description = "Turn a smart-home device on or off."
                override val parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "device": { "type": "string", "description": "Device id or name" },
                        "state": { "type": "string", "enum": ["on", "off"] }
                      },
                      "required": ["device", "state"]
                    }
                """.trimIndent()

                override suspend fun execute(arguments: String): String {
                    return """{"error":"not configured"}"""
                }
            },
            object : ToolContract {
                override val name = "getWeather"
                override val description = "Get the current weather for a location."
                override val parametersJson = """
                    {
                      "type": "object",
                      "properties": {
                        "location": { "type": "string", "description": "City or coordinates" },
                        "units": { "type": "string", "enum": ["celsius", "fahrenheit"] }
                      },
                      "required": ["location"]
                    }
                """.trimIndent()

                override suspend fun execute(arguments: String): String {
                    return """{"error":"not configured"}"""
                }
            }
        )
    )

    fun getAvailableTools(): List<SerializationTool> {
        return toolRegistry.available().map { tool ->
            SerializationTool(
                function = ToolFunction(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.parametersJson
                )
            )
        }
    }

    suspend fun execute(call: FunctionCall): ToolResult {
        return toolRegistry.execute(call)
    }
}