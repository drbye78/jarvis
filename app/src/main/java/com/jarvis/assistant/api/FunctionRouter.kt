package com.jarvis.assistant.api

import android.content.Context
import com.jarvis.assistant.contracts.FunctionCall
import com.jarvis.assistant.contracts.Message
import com.jarvis.assistant.contracts.Tool as SerializationTool
import com.jarvis.assistant.contracts.ToolFunction
import com.jarvis.assistant.contracts.ToolResult
import com.jarvis.assistant.tools.AlarmScheduler
import com.jarvis.assistant.tools.AlarmTool
import com.jarvis.assistant.tools.AndroidAlarmScheduler
import com.jarvis.assistant.tools.DeviceControlAdapter
import com.jarvis.assistant.tools.DeviceControlTool
import com.jarvis.assistant.tools.OpenMeteoWeatherClient
import com.jarvis.assistant.tools.ToolRegistry
import com.jarvis.assistant.tools.UnconfiguredDeviceControlAdapter
import com.jarvis.assistant.tools.WeatherClient
import com.jarvis.assistant.tools.WeatherTool

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
    context: Context,
    private val historyProvider: suspend () -> List<Message> = { emptyList() }
) {
    private val alarmScheduler: AlarmScheduler = AndroidAlarmScheduler(context)
    private val weatherClient: WeatherClient = OpenMeteoWeatherClient()
    private val deviceAdapter: DeviceControlAdapter = UnconfiguredDeviceControlAdapter()

    private val toolRegistry = ToolRegistry(
        listOf(
            AlarmTool(alarmScheduler),
            WeatherTool(weatherClient),
            DeviceControlTool(deviceAdapter)
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