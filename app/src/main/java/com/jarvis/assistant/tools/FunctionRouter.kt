package com.jarvis.assistant.tools

import android.content.Context
import android.os.Build
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.media.AndroidMediaGateway
import com.jarvis.assistant.media.MusicPlaybackOrchestrator
import com.jarvis.assistant.model.FunctionCall
import com.jarvis.assistant.model.ToolDefinition
import com.jarvis.assistant.tools.weather.AndroidLocationProvider
import com.jarvis.assistant.tools.weather.DefaultWeatherLocationResolver
import com.jarvis.assistant.tools.weather.LocationProvider
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
    /**
     * COGNITIVE_PLAN 0.7: ONE [AppPrefs] instance, injected from the graph —
     * the router used to construct its own, so two instances of the same
     * prefs file lived side by side and future cognitive tools would have
     * read a different instance than the session layer.
     */
    val appPrefs: com.jarvis.assistant.util.AppPrefs,
    /**
     * COGNITIVE_PLAN 1.5: the memory tools (remember_fact / recall_facts /
     * forget_fact), resolved LAZILY so registering them never forces the
     * cognitive coordinator's first DB touch at graph construction.
     */
    private val cognitiveTools: () -> List<ToolContract> = { emptyList() },
    /**
     * COGNITIVE_PLAN 2.1: telemetry observer passed through to the
     * ToolRegistry (command_events). Null = no telemetry (tests).
     */
    private val executionObserver: (
        suspend (
            com.jarvis.assistant.model.FunctionCall,
            ToolResult,
            Long
        ) -> Unit
    )? = null,
    /**
     * DI fix: the alarm scheduler is injected by the composition root
     * ([com.jarvis.assistant.di.AppGraph]) — the router used to build it via
     * `AppDatabase.getInstance` directly, bypassing the graph for wiring.
     */
    private val alarmScheduler: AndroidAlarmScheduler,
    /**
     * Weather/GPS timing + Open-Meteo base URLs. Injected so the values stay in
     * the one config file rather than being hardcoded in the tool.
     */
    private val config: JarvisConfig = JarvisConfig(),
    /**
     * Device-location seam. Defaults to the framework `LocationManager`
     * implementation; injectable so a test can supply a scripted fix (or a
     * no-op) without touching the Android location stack.
     */
    locationProvider: LocationProvider? = null,
) : ToolExecutor {
    private val appContext = context.applicationContext

    /** GMS-free: framework LocationManager only (see AndroidLocationProvider). */
    private val weatherLocationProvider: LocationProvider =
        locationProvider ?: AndroidLocationProvider(appContext)

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

    private val baseToolRegistry = ToolRegistry(
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
                    forecastDays = config.weatherForecastDays,
                    geoBaseUrl = config.openMeteoGeocodingBaseUrl,
                    forecastBaseUrl = config.openMeteoForecastBaseUrl,
                ),
                // Configured city wins (read LIVE, so a Settings change applies
                // to the next turn); else a bounded GPS fix; else an honest
                // typed failure the tool turns into a spoken hint.
                resolver = DefaultWeatherLocationResolver(
                    configuredLocation = { appPrefs.weatherLocation },
                    provider = weatherLocationProvider,
                    coordsLabel = { appContext.getString(com.jarvis.assistant.R.string.weather_location_current) },
                    maxAgeMs = config.weatherLastKnownMaxAgeMs,
                    timeoutMs = config.weatherGpsFixTimeoutMs,
                ),
                // Locale-aware phrases via the SAME ToolStrings seam every other
                // tool uses (ToolStrings extends WeatherToolMessages).
                messages = toolStrings,
                // GPS + geocode + forecast can exceed the 15 s registry default.
                budgetMs = config.weatherToolTimeoutMs,
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

    /**
     * The full registry = base tools + memory tools. Lazy: the cognitive
     * coordinator (and its Room v4 migration) resolves on first LLM pass
     * or first tool call, not at graph construction (§9.4 startup budget).
     */
    private val toolRegistry by lazy {
        ToolRegistry(
            tools = baseToolRegistry.available() + cognitiveTools(),
            onExecuted = executionObserver,
        )
    }

    override fun getToolDefinitions(): List<ToolDefinition> =
        toolRegistry.getToolDefinitions()

    /** Structured outcome (m1): classification via [ToolResult.isError]. */
    override suspend fun executeResult(call: FunctionCall): ToolResult =
        toolRegistry.executeResult(call)
}
