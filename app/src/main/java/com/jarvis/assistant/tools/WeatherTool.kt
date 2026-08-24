package com.jarvis.assistant.tools

import com.jarvis.assistant.contracts.ToolContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class WeatherTool(private val weatherClient: WeatherClient) : ToolContract {
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

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): String {
        val obj = try {
            json.parseToJsonElement(arguments).jsonObject
        } catch (e: Exception) {
            return """{"error":"Invalid JSON arguments"}"""
        }
        val location = obj["location"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"Missing required parameter: location"}"""
        val units = obj["units"]?.jsonPrimitive?.contentOrNull ?: "celsius"
        return try {
            weatherClient.getWeather(location, units)
        } catch (e: Exception) {
            """{"error":"Weather lookup failed: ${e.message}"}"""
        }
    }
}