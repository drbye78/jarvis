package com.jarvis.assistant.weather

import com.jarvis.assistant.llm.await
import com.jarvis.assistant.location.ResolvedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Open-Meteo (free, no key): geocode a [ResolvedLocation.Place] and then fetch
 * current conditions PLUS a dated daily forecast. A [ResolvedLocation.Coords]
 * request skips geocoding entirely and reports under its own [label]. All
 * output is built with kotlinx.serialization — no string interpolation into
 * JSON (the original produced invalid JSON when the temperature field was
 * missing, writing `"temp":?`). Names are URL-encoded to prevent query
 * injection.
 *
 * Condition names resolve through [conditionFor] so production can pass
 * the locale-aware `weather_*` string resources (values/ AND values-en/ ship
 * all 15 — the translations existed but were dead resources). The default
 * keeps the original RU literals for non-Android callers and tests.
 *
 * The geocoding language now follows [languageTag] (device locale
 * in production — it was hardcoded to "ru"), the geocoder is asked for the
 * top-5 candidates, and an EXACT name match is preferred over the raw first
 * hit (disambiguation: "Санкт-Петербург" must not resolve to a same-named
 * village). The response carries `location` + `country` so the LLM can state
 * WHICH city answered. Missing readings render through [notAvailable] instead
 * of a hardcoded Russian "н/д".
 *
 * FORECAST lane: `daily` is COLUMN-ORIENTED (parallel arrays) and MANDATES
 * `timezone=auto` — without it Open-Meteo shifts day boundaries to GMT. Rows
 * are therefore assembled BY INDEX (day i's date is always paired with day i's
 * readings) and a gap in one column degrades to [notAvailable] rather than
 * misaligning the rest. Weekdays are derived from the date with [languageTag]'s
 * locale, never hardcoded.
 */
class OpenMeteoWeatherClient(
    private val httpClient: OkHttpClient,
    private val conditionFor: (Int?) -> String = ::weatherCodeToRussianDefault,
    /** BCP-47-ish geocoding language ("ru", "en", ...) — open-meteo supports it natively. */
    private val languageTag: String = "ru",
    /** Placeholder for missing readings (locale-aware in production). */
    private val notAvailable: String = "н/д",
    /** Default horizon when a [WeatherQuery] asks for a non-positive day count. */
    private val forecastDays: Int = DEFAULT_FORECAST_DAYS,
    /**
     * Base-URL seams so the JVM tests
     * run against MockWebServer instead of the REAL open-meteo endpoints —
     * a unit test that calls the live internet hangs sandboxes/CI without
     * egress and is non-deterministic everywhere else. Defaults keep
     * production behavior byte-identical.
     */
    private val geoBaseUrl: String = "https://geocoding-api.open-meteo.com",
    private val forecastBaseUrl: String = "https://api.open-meteo.com",
) : WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome =
        withContext(Dispatchers.IO) {
            val days = normalizeDays(query.days)
            when (val location = query.location) {
                is ResolvedLocation.Place -> fromPlace(location, days)
                is ResolvedLocation.Coords -> fromCoords(location, days)
            }
        }

    /** 1..7, falling back to [forecastDays] when the caller sent no valid day. */
    private fun normalizeDays(days: Int): Int =
        (if (days >= 1) days else forecastDays).coerceIn(1, MAX_FORECAST_DAYS)

    private suspend fun fromPlace(place: ResolvedLocation.Place, days: Int): WeatherOutcome {
        val encoded = URLEncoder.encode(place.name.trim(), "UTF-8")
        val lang = languageTag.ifBlank { "ru" }
        val geoUrl = (
            "$geoBaseUrl/v1/search" +
                "?name=$encoded&count=5&language=$lang"
            ).toHttpUrl()

        val geoBody = when (val response = httpGet(geoUrl.toString())) {
            is HttpResult.Body -> response.text
            else -> return unreachableOrBad(response)
        }
        val geoJson = runCatching { json.parseToJsonElement(geoBody).jsonObject }
            .getOrNull() ?: return WeatherOutcome.Error("Bad geocoding response", unreachable = false)

        val results = geoJson["results"]?.jsonArray
        if (results.isNullOrEmpty()) {
            return WeatherOutcome.Error("Location not found: ${place.name}", unreachable = false)
        }
        // Prefer an exact-name match among the candidates; else the first hit.
        val first = results
            .map { it.jsonObject }
            .firstOrNull { candidate ->
                candidate["name"]?.jsonPrimitive?.contentOrNull
                    ?.equals(place.name.trim(), ignoreCase = true) == true
            }
            ?: results[0].jsonObject
        // Resolve both coordinates in ONE step so the failure path is a single
        // return (a per-field early return pushed this function over the
        // detekt ReturnCount budget).
        val coordinates = first.coordinates()
            ?: return WeatherOutcome.Error("Could not resolve coordinates", unreachable = false)
        val displayName = first["name"]?.jsonPrimitive?.contentOrNull ?: place.name
        val country = first["country"]?.jsonPrimitive?.contentOrNull

        return fetchForecast(coordinates.first, coordinates.second, displayName, country, days)
    }

    /** Latitude/longitude pair, or null when either is absent/unparseable. */
    private fun JsonObject.coordinates(): Pair<String, String>? {
        val lat = this["latitude"]?.jsonPrimitive?.contentOrNull ?: return null
        val lon = this["longitude"]?.jsonPrimitive?.contentOrNull ?: return null
        return lat to lon
    }

    /** GPS coordinates: no geocoding, no country — the label is what we report. */
    private suspend fun fromCoords(coords: ResolvedLocation.Coords, days: Int): WeatherOutcome =
        fetchForecast(coords.latitude.toString(), coords.longitude.toString(), coords.label, null, days)

    private suspend fun fetchForecast(
        latitude: String,
        longitude: String,
        displayName: String,
        country: String?,
        days: Int,
    ): WeatherOutcome {
        val weatherUrl = (
            "$forecastBaseUrl/v1/forecast" +
                "?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code," +
                "wind_speed_10m,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
                "precipitation_probability_max,wind_speed_10m_max" +
                "&forecast_days=$days" +
                "&timezone=auto"
            ).toHttpUrl()

        val weatherBody = when (val response = httpGet(weatherUrl.toString())) {
            is HttpResult.Body -> response.text
            else -> return unreachableOrBad(response)
        }
        val weatherJson = runCatching { json.parseToJsonElement(weatherBody).jsonObject }
            .getOrNull() ?: return WeatherOutcome.Error("Bad weather response", unreachable = false)

        val current = weatherJson["current"]?.jsonObject
            ?: return WeatherOutcome.Error("No current conditions", unreachable = false)
        val daily = weatherJson["daily"]?.let { runCatching { it.jsonObject }.getOrNull() }

        return WeatherOutcome.Ok(
            buildJsonObject {
                put("location", JsonPrimitive(displayName))
                if (!country.isNullOrBlank()) put("country", JsonPrimitive(country))
                put("current", buildCurrent(current))
                put("daily", buildDaily(daily))
            }.toString(),
        )
    }

    private fun buildCurrent(current: JsonObject): JsonObject = buildJsonObject {
        putDoubleOrNA("temp", current["temperature_2m"]?.jsonPrimitive?.contentOrNull)
        putDoubleOrNA("feels_like", current["apparent_temperature"]?.jsonPrimitive?.contentOrNull)
        val code = current["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        put("condition", JsonPrimitive(conditionFor(code)))
        putDoubleOrNA("wind_kmh", current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull)
        putIntOrNA("humidity", current["relative_humidity_2m"]?.jsonPrimitive?.contentOrNull)
        putDoubleOrNA("precipitation", current["precipitation"]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * Assembles the daily rows BY INDEX. `daily` is column-oriented: the row at
     * index i must draw every field from index i of its own column, so a short
     * or null-padded column can never pair day i's date with day j's reading.
     * A day whose date is absent is skipped; a missing number becomes
     * [notAvailable].
     */
    private fun buildDaily(daily: JsonObject?): JsonArray {
        if (daily == null) return buildJsonArray {}
        val times = daily["time"]?.let { runCatching { it.jsonArray }.getOrNull() }
            ?: return buildJsonArray {}
        return buildJsonArray {
            times.forEachIndexed { index, element ->
                val date = element.jsonPrimitive.contentOrNull
                if (date.isNullOrBlank()) return@forEachIndexed
                add(buildDailyRow(daily, index, date))
            }
        }
    }

    private fun buildDailyRow(daily: JsonObject, index: Int, date: String): JsonObject = buildJsonObject {
        put("date", JsonPrimitive(date))
        put("weekday", JsonPrimitive(weekdayName(date)))
        val code = daily.column("weather_code", index)?.toIntOrNull()
        put("condition", JsonPrimitive(conditionFor(code)))
        putDoubleOrNA("temp_max", daily.column("temperature_2m_max", index))
        putDoubleOrNA("temp_min", daily.column("temperature_2m_min", index))
        putDoubleOrNA("precipitation_mm", daily.column("precipitation_sum", index))
        putIntOrNA("precipitation_probability", daily.column("precipitation_probability_max", index))
        putDoubleOrNA("wind_max_kmh", daily.column("wind_speed_10m_max", index))
    }

    /** One column value at [index]; null when the column is short or absent. */
    private fun JsonObject.column(name: String, index: Int): String? {
        val column = this[name]?.let { runCatching { it.jsonArray }.getOrNull() } ?: return null
        val element = column.getOrNull(index) ?: return null
        return runCatching { element.jsonPrimitive.contentOrNull }.getOrNull()
    }

    private fun JsonObjectBuilder.putDoubleOrNA(key: String, raw: String?) {
        val value = raw?.toDoubleOrNull()
        put(key, if (value == null) JsonPrimitive(notAvailable) else JsonPrimitive(value))
    }

    private fun JsonObjectBuilder.putIntOrNA(key: String, raw: String?) {
        val value = raw?.toIntOrNull() ?: raw?.toDoubleOrNull()?.toInt()
        put(key, if (value == null) JsonPrimitive(notAvailable) else JsonPrimitive(value))
    }

    /** Locale-aware full weekday name, derived from the ISO date. */
    private fun weekdayName(date: String): String {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return notAvailable
        val locale = Locale.forLanguageTag(languageTag.ifBlank { "ru" })
        return runCatching {
            parsed.format(DateTimeFormatter.ofPattern("EEEE", locale))
        }.getOrDefault(notAvailable)
    }

    private fun unreachableOrBad(result: HttpResult): WeatherOutcome.Error =
        WeatherOutcome.Error("Weather service unreachable", unreachable = result is HttpResult.Unreachable)

    private suspend fun httpGet(url: String): HttpResult = try {
        httpClient.newCall(Request.Builder().url(url).build()).await().use { resp ->
            val body = resp.body?.string()
            if (resp.isSuccessful && !body.isNullOrBlank()) {
                HttpResult.Body(body)
            } else {
                // Answered, but unusable: a non-2xx status or an empty body is
                // a reachable server's logical answer, never a failover trigger.
                HttpResult.BadResponse
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // cancellation must not be swallowed into "unreachable"
    } catch (_: Exception) {
        HttpResult.Unreachable
    }

    /** Transport result, kept apart so a non-2xx/blank reply is not "unreachable". */
    private sealed interface HttpResult {
        data class Body(val text: String) : HttpResult
        data object Unreachable : HttpResult
        data object BadResponse : HttpResult
    }
}
