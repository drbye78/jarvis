package com.jarvis.assistant.weather

import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.util.JsonOut

/** A resolved weather request: where + how many forecast days (1..7). */
data class WeatherQuery(val location: ResolvedLocation, val days: Int)

/**
 * A provider outcome. [unreachable] is true ONLY for network-class failures
 * (DNS, TCP connect/refusal, TLS, socket/read timeout) — a reachable server's
 * LOGICAL answer (non-2xx, blank body, malformed payload, "not found", "no
 * data") is NOT a failover trigger. [SelectingWeatherClient] keys its
 * failover on this flag and nothing else.
 */
sealed interface WeatherOutcome {
    data class Ok(val document: String) : WeatherOutcome
    data class Error(val message: String, val unreachable: Boolean) : WeatherOutcome
}

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
    /**
     * The classified outcome. Production clients (Open-Meteo, Project EOL)
     * implement THIS so the selector can tell a network failure from a real
     * answer. The default forwards [getWeather] as an Ok document, which keeps
     * query-capturing test doubles that only implement [getWeather] working
     * unchanged (the selector never wraps such a double).
     */
    suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome =
        WeatherOutcome.Ok(getWeather(query))

    /** Convenience: the document, or an error document. */
    suspend fun getWeather(query: WeatherQuery): String =
        when (val outcome = getWeatherOutcome(query)) {
            is WeatherOutcome.Ok -> outcome.document
            is WeatherOutcome.Error -> JsonOut.error(outcome.message)
        }
}

/** Voice UX cap and Open-Meteo's practical horizon for a spoken forecast. */
const val MAX_FORECAST_DAYS = 7

/** Fallback day count when a request does not name one. */
const val DEFAULT_FORECAST_DAYS = 7
