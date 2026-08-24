package com.jarvis.assistant.tools

import com.jarvis.assistant.contracts.ToolContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class DeviceControlTool(private val adapter: DeviceControlAdapter) : ToolContract {
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

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): String {
        val obj = try {
            json.parseToJsonElement(arguments).jsonObject
        } catch (e: Exception) {
            return """{"error":"Invalid JSON arguments"}"""
        }
        val device = obj["device"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"Missing required parameter: device"}"""
        val stateStr = obj["state"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"Missing required parameter: state"}"""
        if (stateStr !in listOf("on", "off")) return """{"error":"state must be 'on' or 'off'"}"""
        val state = stateStr == "on"
        return try {
            adapter.setState(device, state)
        } catch (e: Exception) {
            """{"error":"Device control failed: ${e.message}"}"""
        }
    }
}