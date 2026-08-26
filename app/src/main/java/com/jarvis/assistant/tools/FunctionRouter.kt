package com.jarvis.assistant.tools

import android.content.Context
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.ToolDefinition
import okhttp3.OkHttpClient

/**
 * Composition root for the tool framework: builds every REAL tool (alarms,
 * timers, weather, on-tablet device control) and exposes the registry to the
 * session layer. The old `UnconfiguredDeviceControlAdapter` stub is gone —
 * every advertised capability actually works or returns an instructive error.
 */
class FunctionRouter(
    context: Context,
    httpClient: OkHttpClient,
) : ToolExecutor {
    private val appContext = context.applicationContext

    private val alarmScheduler = AndroidAlarmScheduler(
        appContext,
        com.jarvis.assistant.data.AppDatabase.getInstance(appContext).alarmDao(),
    )

    private val toolRegistry = ToolRegistry(
        listOf(
            SetAlarmTool(alarmScheduler),
            CancelAlarmTool(appContext, alarmScheduler),
            ListAlarmsTool(appContext),
            SetTimerTool(alarmScheduler),
            CancelTimerTool(appContext, alarmScheduler),
            WeatherTool(OpenMeteoWeatherClient(httpClient)),
        ) + DeviceTools(appContext).all(),
    )

    override fun getToolDefinitions(): List<ToolDefinition> =
        toolRegistry.getToolDefinitions()

    /** Structured outcome (m1): classification via [ToolResult.isError]. */
    suspend fun executeResult(call: FunctionCall): ToolResult =
        toolRegistry.executeResult(call)

    /**
     * Legacy facade kept so out-of-lane callers compile unchanged; routes
     * through the structured API. P7 removes.
     */
    @Deprecated("P7 removes")
    override suspend fun execute(call: FunctionCall): ToolExecution =
        executeResult(call).let { ToolExecution(call, it.content, it.isError) }
}
