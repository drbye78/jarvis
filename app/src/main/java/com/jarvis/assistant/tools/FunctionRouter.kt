package com.jarvis.assistant.tools

import android.content.Context
import android.os.Build
import com.jarvis.assistant.media.AndroidMediaGateway
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.ToolDefinition
import okhttp3.OkHttpClient

/**
 * Composition root for the tool framework: builds every REAL tool (alarms,
 * timers, weather, on-tablet device control, external music playback) and
 * exposes the registry to the session layer. The old
 * `UnconfiguredDeviceControlAdapter` stub is gone — every advertised
 * capability actually works or returns an instructive error.
 */
class FunctionRouter(
    context: Context,
    httpClient: OkHttpClient,
    /** Phase 5 (M5): spoken cascade progress; null = silent cascade. */
    speechFeedback: com.jarvis.assistant.audio.SpeechFeedback? = null,
) : ToolExecutor {
    private val appContext = context.applicationContext

    private val alarmScheduler = AndroidAlarmScheduler(
        appContext,
        com.jarvis.assistant.data.AppDatabase.getInstance(appContext).alarmDao(),
    )

    // Preferred default music player from Settings («Музыка» card): a package
    // name, or null for "auto" (Яндекс Музыка first). Read lazily on every
    // resolve so a Settings change applies to the NEXT voice command without
    // a service restart (same contract as the wake-word reconfigure path).
    private val mediaGateway = AndroidMediaGateway(
        appContext,
        preferredPlayerPackage = {
            com.jarvis.assistant.util.AppPrefs(appContext)
                .preferredMusicPlayer.takeUnless { it == "auto" }
        },
    )

    private val toolRegistry = ToolRegistry(
        listOf(
            SetAlarmTool(alarmScheduler),
            CancelAlarmTool(appContext, alarmScheduler),
            ListAlarmsTool(appContext),
            SetTimerTool(alarmScheduler),
            CancelTimerTool(appContext, alarmScheduler),
            WeatherTool(OpenMeteoWeatherClient(httpClient)),
        ) + DeviceTools(appContext).all() +
            MusicTools(
                MusicPlaybackOrchestrator(
                    mediaGateway,
                    mediaGateway.resolver,
                    mediaGateway.browserGateway,
                    deviceApiLevel = Build.VERSION.SDK_INT,
                    feedback = speechFeedback,
                ),
            ).all(),
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
