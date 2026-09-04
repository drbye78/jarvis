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
    /** A4: tool-layer error strings (locale-aware in production). */
    toolStrings: ToolStrings = ToolStrings.Default,
    /** A6: geocoding language — follows the device locale in production. */
    weatherLanguageTag: String = "ru",
) : ToolExecutor {
    private val appContext = context.applicationContext

    // A10: ONE prefs instance — the per-resolve lambda used to construct a
    // fresh AppPrefs on every music target resolution.
    private val appPrefs = com.jarvis.assistant.util.AppPrefs(appContext)

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
            appPrefs.preferredMusicPlayer.takeUnless { it == "auto" }
        },
    )

    private val toolRegistry = ToolRegistry(
        listOf(
            SetAlarmTool(
                alarmScheduler,
                // F6: locale-aware defaults — the values-en translations
                // existed but were never wired (dead resources).
                defaultLabel = { appContext.getString(com.jarvis.assistant.R.string.default_alarm_label) },
            ),
            CancelAlarmTool(appContext, alarmScheduler),
            ListAlarmsTool(appContext),
            SetTimerTool(
                alarmScheduler,
                defaultLabel = { appContext.getString(com.jarvis.assistant.R.string.default_timer_label) },
            ),
            CancelTimerTool(appContext, alarmScheduler),
            WeatherTool(
                OpenMeteoWeatherClient(
                    httpClient,
                    // F6: condition names follow the device locale.
                    conditionFor = { code ->
                        com.jarvis.assistant.tools.weatherConditionName(appContext, code)
                    },
                    // A6: geocoding answers in the device language; missing
                    // readings render locale-aware instead of a hardcoded «н/д».
                    languageTag = weatherLanguageTag,
                    notAvailable = toolStrings.weatherNotAvailable,
                ),
            ),
        ) + DeviceTools(appContext, toolStrings).all() +
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
    override suspend fun executeResult(call: FunctionCall): ToolResult =
        toolRegistry.executeResult(call)
}
