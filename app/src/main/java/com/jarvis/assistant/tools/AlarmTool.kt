package com.jarvis.assistant.tools

import com.jarvis.assistant.contracts.ToolContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class AlarmTool(private val scheduler: AlarmScheduler) : ToolContract {
    override val name = "setAlarm"
    override val description = "Set an alarm at a given time."
    override val parametersJson = """
        {
          "type": "object",
          "properties": {
            "time": { "type": "string", "description": "Time in HH:mm format, e.g. 07:30" },
            "label": { "type": "string", "description": "Optional alarm label" }
          },
          "required": ["time"]
        }
    """.trimIndent()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(arguments: String): String {
        val obj = try {
            json.parseToJsonElement(arguments).jsonObject
        } catch (e: Exception) {
            return """{"error":"Invalid JSON arguments"}"""
        }
        val timeStr = obj["time"]?.jsonPrimitive?.contentOrNull
            ?: return """{"error":"Missing required parameter: time"}"""
        val parts = timeStr.split(":")
        if (parts.size != 2) return """{"error":"Invalid time format. Use HH:mm"}"""
        val hour = parts[0].toIntOrNull() ?: return """{"error":"Invalid hour"}"""
        val minute = parts[1].toIntOrNull() ?: return """{"error":"Invalid minute"}"""
        if (hour !in 0..23 || minute !in 0..59) return """{"error":"Time out of range"}"""
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: "Будильник"
        scheduler.schedule(label, hour, minute)
        return """{"status":"scheduled","time":"$timeStr","label":"$label"}"""
    }
}