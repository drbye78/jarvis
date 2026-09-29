package com.jarvis.assistant.weather

import com.jarvis.assistant.location.ResolvedLocation

/** A resolved weather request: where + how many forecast days (1..7). */
data class WeatherQuery(val location: ResolvedLocation, val days: Int)

/**
 * The weather CAPABILITY. Mirrors the geo lane's shape (`GeoToolClient`): a
 * narrow interface with one implementation per data source, no Settings
 * awareness of its own. Provider SELECTION lives in [SelectingWeatherClient],
 * so a client never has to know it might be swapped out.
 *
 * Implementations return the SAME JSON document shape (see
 * [WeatherContract]) so the LLM sees one vocabulary regardless of provider —
 * even though the upstream wire formats differ completely (Open-Meteo's
 * column-oriented REST vs Project EOL's hourly MCP values).
 */
interface WeatherClient {
    suspend fun getWeather(query: WeatherQuery): String
}

/** Voice UX cap and Open-Meteo's practical horizon for a spoken forecast. */
const val MAX_FORECAST_DAYS = 7

/** Fallback day count when a request does not name one. */
const val DEFAULT_FORECAST_DAYS = 7
