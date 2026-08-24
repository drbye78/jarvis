package com.jarvis.assistant.api

import android.content.Context
import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.Tool
import com.jarvis.assistant.contracts.ToolFunction
import com.jarvis.assistant.contracts.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Registry and executor for assistant tools.
 *
 * Decoupled from the data layer: it accepts a [historyProvider] lambda
 * (`() -> List<Message>`) instead of importing a concrete ConversationManager,
 * so it can be wired up once that type exists without a cross-phase dependency.
 *
 * [execute] runs on [Dispatchers.IO] and always returns a [ToolResult] — unknown
 * tool names produce a clear error result rather than throwing.
 */
class FunctionRouter(
    @Suppress("unused") context: Context,
    private val historyProvider: suspend () -> List<Message> = { emptyList() }
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Example tool catalogue (JSON-schema parameters kept as raw strings). */
    fun getAvailableTools(): List<Tool> = listOf(
        Tool(
            function = ToolFunction(
                name = "setAlarm",
                description = "Set an alarm at a given time.",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "time": { "type": "string", "description": "ISO-8601 time, e.g. 07:30" },
                        "label": { "type": "string", "description": "Optional alarm label" }
                      },
                      "required": ["time"]
                    }
                """.trimIndent()
            )
        ),
        Tool(
            function = ToolFunction(
                name = "controlDevice",
                description = "Turn a smart-home device on or off.",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "device": { "type": "string", "description": "Device id or name" },
                        "state": { "type": "string", "enum": ["on", "off"] }
                      },
                      "required": ["device", "state"]
                    }
                """.trimIndent()
            )
        ),
        Tool(
            function = ToolFunction(
                name = "getWeather",
                description = "Get the current weather for a location.",
                parameters = """
                    {
                      "type": "object",
                      "properties": {
                        "location": { "type": "string", "description": "City or coordinates" },
                        "units": { "type": "string", "enum": ["celsius", "fahrenheit"] }
                      },
                      "required": ["location"]
                    }
                """.trimIndent()
            )
        )
    )

    suspend fun execute(call: FunctionCall): ToolResult = withContext(Dispatchers.IO) {
        val result = when (call.name) {
            "setAlarm" -> setAlarm(call.arguments)
            "controlDevice" -> controlDevice(call.arguments)
            "getWeather" -> getWeather(call.arguments)
            else -> """{"error":"Unknown function: ${call.name}"}"""
        }
        ToolResult(call, result)
    }

    private fun setAlarm(args: String): String {
        val obj = parseArgs(args) ?: return """{"error":"invalid arguments"}"""
        val time = obj["time"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"missing required parameter: time"}"""
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: ""
        // STUB: a real implementation would schedule via AlarmManager.
        return """{"status":"scheduled","time":"$time","label":"$label"}"""
    }

    private fun controlDevice(args: String): String {
        val obj = parseArgs(args) ?: return """{"error":"invalid arguments"}"""
        val device = obj["device"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"missing required parameter: device"}"""
        val state = obj["state"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"missing required parameter: state"}"""
        // STUB: a real implementation would talk to the smart-home hub.
        return """{"status":"ok","device":"$device","state":"$state"}"""
    }

    private fun getWeather(args: String): String {
        val obj = parseArgs(args) ?: return """{"error":"invalid arguments"}"""
        val location = obj["location"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"missing required parameter: location"}"""
        val units = obj["units"]?.jsonPrimitive?.contentOrNull ?: "celsius"
        // STUB: a real implementation would call a weather provider.
        return """{"location":"$location","units":"$units","temp":21,"condition":"clear"}"""
    }

    private fun parseArgs(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        null
    }
}
