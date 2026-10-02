package com.jarvis.assistant.weather

import com.jarvis.assistant.location.ResolvedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.round

/**
 * Project EOL weather provider — MCP over Streamable HTTP at
 * [com.jarvis.assistant.config.JarvisConfig.projectEolMcpUrl].
 *
 * This is a fundamentally different feed from [OpenMeteoWeatherClient] and the
 * differences are handled here so the rest of the app never sees them:
 *
 *  1. **Transport is MCP, not REST.** Every reading is a `tools/call`
 *     JSON-RPC round trip ([StreamableMcpToolClient]); there is no query string
 *     to build and no API key.
 *  2. **No geocoding in the forecast call.** A place name must first be resolved
 *     through `search_locations`, which returns coordinates; the forecast is
 *     then fetched by coordinate. (Open-Meteo geocodes inside the same client.)
 *  3. **Hourly, never daily.** The server publishes an HOURLY series and caps a
 *     response at 168 hours. The daily rows the LLM relies on are therefore
 *     AGGREGATED here, in the device's time zone — see [aggregate].
 *  4. **Units are physical, not consumer.** Temperature is KELVIN, wind is
 *     METRES PER SECOND, precipitation is kg m⁻² (≈ mm). Converted at the edge.
 *  5. **There is no weather code.** The upstream is a raw NOAA GFS feed, so the
 *     spoken condition is DERIVED from precipitation + temperature + cloud
 *     fraction by [deriveWmoCode]. A model upgrade cannot silently change a
 *     spoken phrase, because the code is ours.
 *  6. **No apparent temperature and no precipitation probability from the
 *     server, so `feels_like` is DERIVED** by [apparentTemperatureC] (an
 *     estimate, never presented as a reading). Precipitation probability is
 *     genuinely unavailable and is OMITTED rather than faked.
 *
 * All output goes through [weatherDocument] so the JSON shape matches the other
 * provider exactly.
 */
class ProjectEolWeatherClient(
    private val mcp: McpToolClient,
    private val conditionFor: (Int?) -> String = ::weatherCodeToRussianDefault,
    private val notAvailable: String = "н/д",
    private val forecastDays: Int = DEFAULT_FORECAST_DAYS,
    /** Clock seam so the "current hour" and day boundaries are testable. */
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Device zone: the server speaks UTC, the user does not. */
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome =
        withContext(Dispatchers.IO) {
            val days = (if (query.days >= 1) query.days else forecastDays).coerceIn(1, MAX_FORECAST_DAYS)
            when (val location = query.location) {
                is ResolvedLocation.Place -> fromPlace(location, days)
                is ResolvedLocation.Coords -> fetchForecast(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    displayName = location.label,
                    country = null,
                    days = days,
                )
            }
        }

    /**
     * `search_locations` → coordinates; exact name match preferred, else the
     * first hit. Split out of [fromPlace] so each function keeps a single
     * failure-free return (the house pattern — a per-field early return pushed
     * the Open-Meteo equivalent over the detekt ReturnCount budget).
     */
    private sealed interface PlaceResolution {
        class Ok(val latitude: Double, val longitude: Double, val name: String, val country: String?) :
            PlaceResolution

        data object Unreachable : PlaceResolution
        data object BadResponse : PlaceResolution
        data object NotFound : PlaceResolution
        data object Malformed : PlaceResolution
    }

    private suspend fun fromPlace(place: ResolvedLocation.Place, days: Int): WeatherOutcome =
        when (val resolved = resolvePlace(place.name.trim())) {
            is PlaceResolution.Ok ->
                fetchForecast(resolved.latitude, resolved.longitude, resolved.name, resolved.country, days)

            PlaceResolution.Unreachable ->
                WeatherOutcome.Error("Weather service unreachable", unreachable = true)

            PlaceResolution.BadResponse ->
                WeatherOutcome.Error("Weather service unreachable", unreachable = false)

            PlaceResolution.NotFound ->
                WeatherOutcome.Error("Location not found: ${place.name}", unreachable = false)

            PlaceResolution.Malformed ->
                WeatherOutcome.Error("Bad geocoding response", unreachable = false)
        }

    private suspend fun resolvePlace(name: String): PlaceResolution {
        val args = buildJsonObject {
            put("query", JsonPrimitive(name))
            put("limit", JsonPrimitive(SEARCH_CANDIDATES))
        }.toString()

        val call = mcp.callTool(SEARCH_LOCATIONS, args)
        if (call !is McpCall.Ok) {
            return if (call is McpCall.Unreachable) {
                PlaceResolution.Unreachable
            } else {
                PlaceResolution.BadResponse
            }
        }
        val result = call.result
        if (result.isError) return PlaceResolution.Malformed

        val root = runCatching { json.parseToJsonElement(result.text).jsonObject }.getOrNull()
            ?: return PlaceResolution.Malformed
        val candidates = root["results"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
            .orEmpty()
        if (candidates.isEmpty()) return PlaceResolution.NotFound

        val hit = candidates.firstOrNull { candidate ->
            candidate["name"]?.jsonPrimitive?.contentOrNull?.equals(name, ignoreCase = true) == true
        } ?: candidates.first()
        val latitude = hit["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = hit["longitude"]?.jsonPrimitive?.doubleOrNull

        return if (latitude != null && longitude != null) {
            PlaceResolution.Ok(
                latitude = latitude,
                longitude = longitude,
                name = hit["name"]?.jsonPrimitive?.contentOrNull ?: name,
                country = hit["country"]?.jsonPrimitive?.contentOrNull,
            )
        } else {
            PlaceResolution.Malformed
        }
    }

    private suspend fun fetchForecast(
        latitude: Double,
        longitude: Double,
        displayName: String,
        country: String?,
        days: Int,
    ): WeatherOutcome {
        val zoneId = zone()
        val start = startOfToday(zoneId)
        val args = buildJsonObject {
            put("latitude", JsonPrimitive(latitude))
            put("longitude", JsonPrimitive(longitude))
            put("start", JsonPrimitive(DateTimeFormatter.ISO_INSTANT.format(start)))
            put("hours", JsonPrimitive(days * HOURS_PER_DAY))
            put("parameters", buildJsonArray { FORECAST_PARAMETERS.forEach { add(JsonPrimitive(it)) } })
        }.toString()

        val call = mcp.callTool(GET_WEATHER_FORECAST, args)
        if (call !is McpCall.Ok) {
            return WeatherOutcome.Error(
                "Weather service unreachable",
                unreachable = call is McpCall.Unreachable,
            )
        }
        val result = call.result
        if (result.isError) return WeatherOutcome.Error("Weather service error", unreachable = false)
        val root = runCatching { json.parseToJsonElement(result.text).jsonObject }.getOrNull()
            ?: return WeatherOutcome.Error("Bad weather response", unreachable = false)

        val hours = parseHours(root, zoneId)
        if (hours.isEmpty()) return WeatherOutcome.Error("No forecast data", unreachable = false)

        return WeatherOutcome.Ok(
            weatherDocument(
                displayName = displayName,
                country = country,
                current = currentReadings(hours),
                daily = dailyRows(hours),
                hourly = hourlyRows(hours, zoneId),
            ),
        )
    }

    /** Midnights are the user's, not UTC's — the server speaks UTC only. */
    private fun startOfToday(zoneId: ZoneId): Instant =
        Instant.ofEpochMilli(nowMs()).atZone(zoneId).toLocalDate().atStartOfDay(zoneId).toInstant()

    /** One server hour, already unit-converted and annotated with its LOCAL date. */
    private class Hour(
        val instant: Instant,
        val date: LocalDate,
        val temperatureC: Double?,
        val precipitationMm: Double?,
        val windKmh: Double?,
        val humidity: Double?,
        val cloudFraction: Double?,
    )

    private fun parseHours(root: JsonObject, zoneId: ZoneId): List<Hour> {
        val series = root["forecast"]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return emptyList()
        return series.mapNotNull { element ->
            val entry = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val instant = entry["time"]?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            val values = entry["values"]?.let { runCatching { it.jsonObject }.getOrNull() }
                ?: return@mapNotNull null
            Hour(
                instant = instant,
                date = instant.atZone(zoneId).toLocalDate(),
                temperatureC = values.value(TEMP_2M)?.let(::kelvinToCelsius),
                precipitationMm = values.value(PRECIPITATION_AMOUNT),
                windKmh = values.value(WIND_SPEED_10M)?.let(::metresPerSecondToKmh),
                humidity = values.value(HUMIDITY_2M),
                cloudFraction = values.value(CLOUD_FRACTION),
            )
        }
    }

    /** Forward hours from "now" (the first not in the past), capped at [limit]. */
    private fun nextHours(hours: List<Hour>, limit: Int): List<Hour> {
        val now = Instant.ofEpochMilli(nowMs())
        return hours.asSequence().filter { !it.instant.isBefore(now) }.take(limit).toList()
    }

    /** The hour nearest to "now" — the first not in the past, else the last. */
    private fun currentReadings(hours: List<Hour>): JsonObject {
        val hour = nextHours(hours, 1).firstOrNull() ?: hours.last()
        return buildJsonObject {
            hour.temperatureC?.let { put(WeatherContract.TEMP, JsonPrimitive(round1(it))) }
            apparentTemperatureC(hour.temperatureC, hour.windKmh, hour.humidity)
                ?.let { put(WeatherContract.FEELS_LIKE, JsonPrimitive(it)) }
            put(
                WeatherContract.CONDITION,
                JsonPrimitive(conditionFor(deriveWmoCode(hour.precipitationMm, hour.temperatureC, hour.cloudFraction))),
            )
            hour.windKmh?.let { put(WeatherContract.WIND_KMH, JsonPrimitive(round1(it))) }
            hour.humidity?.let { put(WeatherContract.HUMIDITY, JsonPrimitive(it.toInt())) }
            hour.precipitationMm?.let { put(WeatherContract.PRECIPITATION, JsonPrimitive(round1(it))) }
        }
    }

    /**
     * The next [HOURLY_SLOTS] hours, reusing the SAME not-in-the-past window as
     * [currentReadings] — no extra server call, no re-fetch. `precipitation_probability`
     * is unavailable from Project EOL and is deliberately omitted.
     */
    private fun hourlyRows(hours: List<Hour>, zoneId: ZoneId): List<JsonObject> =
        nextHours(hours, HOURLY_SLOTS).map { hourlyRow(it, zoneId) }

    private fun hourlyRow(hour: Hour, zoneId: ZoneId): JsonObject = buildJsonObject {
        put(WeatherContract.TIME, JsonPrimitive(localTime(hour.instant, zoneId)))
        hour.temperatureC?.let { put(WeatherContract.TEMP, JsonPrimitive(round1(it))) }
        apparentTemperatureC(hour.temperatureC, hour.windKmh, hour.humidity)
            ?.let { put(WeatherContract.FEELS_LIKE, JsonPrimitive(it)) }
        put(
            WeatherContract.CONDITION,
            JsonPrimitive(conditionFor(deriveWmoCode(hour.precipitationMm, hour.temperatureC, hour.cloudFraction))),
        )
        hour.precipitationMm?.let { put(WeatherContract.PRECIPITATION_MM, JsonPrimitive(round1(it))) }
        hour.windKmh?.let { put(WeatherContract.WIND_KMH, JsonPrimitive(round1(it))) }
    }

    private fun localTime(instant: Instant, zoneId: ZoneId): String =
        runCatching { DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()).format(instant.atZone(zoneId)) }
            .getOrDefault(notAvailable)

    /**
     * Collapses the hourly series into the dated rows the LLM relies on.
     *
     * Per date, in order: temperature MIN/MAX, precipitation SUM (each hourly
     * value is that hour's accumulation, so a day is the sum), wind MAX, and the
     * mean cloud/temperature that drive the derived condition.
     */
    private fun dailyRows(hours: List<Hour>): List<JsonObject> =
        hours.groupBy { it.date }
            .toSortedMap()
            .map { (date, day) -> dailyRow(date, day) }

    private fun dailyRow(date: LocalDate, day: List<Hour>): JsonObject {
        val temps = day.mapNotNull { it.temperatureC }
        val precip = day.mapNotNull { it.precipitationMm }.takeIf { it.isNotEmpty() }?.sum()
        val windMax = day.mapNotNull { it.windKmh }.maxOrNull()
        val cloudMean = day.mapNotNull { it.cloudFraction }.takeIf { it.isNotEmpty() }?.average()
        val tempMean = temps.takeIf { it.isNotEmpty() }?.average()

        return buildJsonObject {
            put(WeatherContract.DATE, JsonPrimitive(date.toString()))
            put(WeatherContract.WEEKDAY, JsonPrimitive(weekdayName(date)))
            put(WeatherContract.CONDITION, JsonPrimitive(conditionFor(deriveWmoCode(precip, tempMean, cloudMean))))
            temps.maxOrNull()?.let { put(WeatherContract.TEMP_MAX, JsonPrimitive(round1(it))) }
            temps.minOrNull()?.let { put(WeatherContract.TEMP_MIN, JsonPrimitive(round1(it))) }
            precip?.let { put(WeatherContract.PRECIPITATION_MM, JsonPrimitive(round1(it))) }
            windMax?.let { put(WeatherContract.WIND_MAX_KMH, JsonPrimitive(round1(it))) }
        }
    }

    private fun weekdayName(date: LocalDate): String {
        val locale = Locale.getDefault()
        return runCatching { date.format(DateTimeFormatter.ofPattern("EEEE", locale)) }
            .getOrDefault(notAvailable)
    }

    private companion object {
        const val HOURS_PER_DAY = 24

        /** Forward hours the `hourly` series carries (a voice-sized window). */
        const val HOURLY_SLOTS = 12
        const val SEARCH_CANDIDATES = 5

        const val SEARCH_LOCATIONS = "search_locations"
        const val GET_WEATHER_FORECAST = "get_weather_forecast"

        const val TEMP_2M = "surface.air_temperature_2m"
        const val PRECIPITATION_AMOUNT = "surface.precipitation_amount"
        const val CLOUD_FRACTION = "surface.cloud_area_fraction"
        const val HUMIDITY_2M = "surface.relative_humidity_2m"
        const val WIND_SPEED_10M = "wind_speed_10m"

        val FORECAST_PARAMETERS = listOf(
            TEMP_2M,
            PRECIPITATION_AMOUNT,
            CLOUD_FRACTION,
            HUMIDITY_2M,
            WIND_SPEED_10M,
        )
    }
}

/** One decimal is the most a spoken forecast can use. */
private fun round1(value: Double): Double = round(value * 10) / 10
