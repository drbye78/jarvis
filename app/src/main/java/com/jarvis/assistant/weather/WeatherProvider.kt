package com.jarvis.assistant.weather

/**
 * The selectable weather data providers. Mirrors
 * [com.jarvis.assistant.settings.ProviderSettings.Type] for the LLM lane: an
 * enum with a stable storage [id], plus a tolerant parser so an unknown or
 * blank stored value degrades to the default instead of crashing.
 *
 * [id] is what lands in `AppPrefs.weatherProvider`; the enum name is NOT
 * persisted, so renaming a constant cannot orphan a stored preference.
 */
enum class WeatherProvider(val id: String) {
    /** Free, keyless REST; geocodes + daily forecast in one hop. The default. */
    OPEN_METEO("open_meteo"),

    /** Free MCP endpoint over NOAA GFS; hourly, aggregated on-device. */
    PROJECT_EOL("project_eol"),
    ;

    companion object {
        /** Tolerant parse: null/blank/unknown → [OPEN_METEO]. */
        fun fromId(raw: String?): WeatherProvider =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: OPEN_METEO
    }
}

/**
 * Routes a [WeatherQuery] to the provider the user selected.
 *
 * The selection is read through [providerFor] on EVERY call rather than
 * snapshotted, so the Settings radio takes effect on the next weather turn
 * without a graph rebuild ([com.jarvis.assistant.settings.ApplyPolicy.LIVE]).
 * The `when` is exhaustive with NO `else`: adding a provider becomes a compile
 * error until it is wired here, the same idiom as the LLM-provider `when` in
 * `AppGraph`.
 */
class SelectingWeatherClient(
    private val providerFor: () -> WeatherProvider,
    private val openMeteo: WeatherClient,
    private val projectEol: WeatherClient,
) : WeatherClient {

    override suspend fun getWeather(query: WeatherQuery): String = when (providerFor()) {
        WeatherProvider.OPEN_METEO -> openMeteo.getWeather(query)
        WeatherProvider.PROJECT_EOL -> projectEol.getWeather(query)
    }
}
