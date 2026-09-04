package com.jarvis.assistant.tools

import com.jarvis.assistant.llm.await
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

interface WeatherClient {
    suspend fun getWeather(location: String): String
}

/**
 * Open-Meteo (free, no key): geocode the location, then fetch current
 * conditions. All output is built with kotlinx.serialization — no string
 * interpolation into JSON (the original produced invalid JSON when the
 * temperature field was missing, writing `"temp":?`).
 * The location is URL-encoded to prevent query injection.
 *
 * F6: condition names resolve through [conditionFor] so production can pass
 * the locale-aware `weather_*` string resources (values/ AND values-en/ ship
 * all 15 — the translations existed but were dead resources). The default
 * keeps the original RU literals for non-Android callers and tests.
 *
 * Audit A6: the geocoding language now follows [languageTag] (device locale
 * in production — it was hardcoded to "ru"), the geocoder is asked for the
 * top-5 candidates, and an EXACT name match is preferred over the raw first
 * hit (disambiguation: "Санкт-Петербург" must not resolve to a same-named
 * village). The response carries `location` + `country` so the LLM can state
 * WHICH city answered. Missing readings render through
 * [notAvailable] instead of a hardcoded Russian "н/д".
 */
class OpenMeteoWeatherClient(
    private val httpClient: OkHttpClient,
    private val conditionFor: (Int?) -> String = ::weatherCodeToRussianDefault,
    /** BCP-47-ish geocoding language ("ru", "en", ...) — open-meteo supports it natively. */
    private val languageTag: String = "ru",
    /** Placeholder for missing readings (locale-aware in production). */
    private val notAvailable: String = "н/д",
) : WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getWeather(location: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(location.trim(), "UTF-8")
        val lang = languageTag.ifBlank { "ru" }
        val geoUrl = ("https://geocoding-api.open-meteo.com/v1/search" +
            "?name=$encoded&count=5&language=$lang").toHttpUrl()

        val geoBody = httpGet(geoUrl.toString())
            ?: return@withContext JsonOut.error("Weather service unreachable")
        val geoJson = runCatching { json.parseToJsonElement(geoBody).jsonObject }
            .getOrNull() ?: return@withContext JsonOut.error("Bad geocoding response")

        val results = geoJson["results"]?.jsonArray
        if (results.isNullOrEmpty()) {
            return@withContext JsonOut.error("Location not found: $location")
        }
        // Prefer an exact-name match among the candidates; else the first hit.
        val first = results
            .map { it.jsonObject }
            .firstOrNull { candidate ->
                candidate["name"]?.jsonPrimitive?.contentOrNull
                    ?.equals(location.trim(), ignoreCase = true) == true
            }
            ?: results[0].jsonObject
        val lat = first["latitude"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext JsonOut.error("Could not resolve coordinates")
        val lon = first["longitude"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext JsonOut.error("Could not resolve coordinates")
        val displayName = first["name"]?.jsonPrimitive?.contentOrNull ?: location
        val country = first["country"]?.jsonPrimitive?.contentOrNull

        val weatherUrl = ("https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&timezone=auto").toHttpUrl()

        val weatherBody = httpGet(weatherUrl.toString())
            ?: return@withContext JsonOut.error("Weather service unreachable")
        val weatherJson = runCatching { json.parseToJsonElement(weatherBody).jsonObject }
            .getOrNull() ?: return@withContext JsonOut.error("Bad weather response")

        val current = weatherJson["current"]?.jsonObject
            ?: return@withContext JsonOut.error("No current conditions")
        val temp = current["temperature_2m"]?.jsonPrimitive?.contentOrNull
        val feels = current["apparent_temperature"]?.jsonPrimitive?.contentOrNull
        val wind = current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull
        val code = current["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

        buildJsonObject {
            put("location", JsonPrimitive(displayName))
            if (!country.isNullOrBlank()) put("country", JsonPrimitive(country))
            put("temp", JsonPrimitive(temp ?: notAvailable))
            put("feels_like", JsonPrimitive(feels ?: notAvailable))
            put("wind_kmh", JsonPrimitive(wind ?: notAvailable))
            put("condition", JsonPrimitive(conditionFor(code)))
        }.toString()
    }

    private suspend fun httpGet(url: String): String? = try {
        httpClient.newCall(Request.Builder().url(url).build()).await().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e // cancellation must not be swallowed into "unreachable"
    } catch (e: Exception) {
        null
    }
}

/** The original RU condition names — the non-Android default. */
private fun weatherCodeToRussianDefault(code: Int?): String = when (code) {
    null -> "неизвестно"
    0 -> "ясно"
    1, 2 -> "малооблачно"
    3 -> "облачно"
    45, 48 -> "туман"
    51, 53, 55 -> "морось"
    56, 57 -> "ледяная морось"
    61, 63, 65 -> "дождь"
    66, 67 -> "ледяной дождь"
    71, 73, 75 -> "снег"
    77 -> "снежные зёрна"
    80, 81, 82 -> "ливень"
    85, 86 -> "снегопад"
    95 -> "гроза"
    96, 99 -> "гроза с градом"
    else -> "облачно"
}

/**
 * F6: locale-aware weather-code → condition-name resolver backed by the
 * `weather_*` string resources. Wired in FunctionRouter so English-locale
 * devices hear "partly cloudy" instead of "малооблачно".
 */
fun weatherConditionName(context: android.content.Context, code: Int?): String =
    context.getString(
        when (code) {
            null -> com.jarvis.assistant.R.string.weather_unknown
            0 -> com.jarvis.assistant.R.string.weather_clear
            1, 2 -> com.jarvis.assistant.R.string.weather_partly_cloudy
            3 -> com.jarvis.assistant.R.string.weather_cloudy
            45, 48 -> com.jarvis.assistant.R.string.weather_fog
            51, 53, 55 -> com.jarvis.assistant.R.string.weather_drizzle
            56, 57 -> com.jarvis.assistant.R.string.weather_icy_drizzle
            61, 63, 65 -> com.jarvis.assistant.R.string.weather_rain
            66, 67 -> com.jarvis.assistant.R.string.weather_icy_rain
            71, 73, 75 -> com.jarvis.assistant.R.string.weather_snow
            77 -> com.jarvis.assistant.R.string.weather_snow_grains
            80, 81, 82 -> com.jarvis.assistant.R.string.weather_rain_shower
            85, 86 -> com.jarvis.assistant.R.string.weather_snow_shower
            95 -> com.jarvis.assistant.R.string.weather_thunderstorm
            96, 99 -> com.jarvis.assistant.R.string.weather_thunderstorm_hail
            else -> com.jarvis.assistant.R.string.weather_cloudy
        },
    )

class WeatherTool(private val weatherClient: WeatherClient) : ToolContract {
    override val name = "getWeather"
    override val description = "Get the current weather for a city or location."
    override val parametersJson = schema(
        mapOf(
            "location" to """{"type":"string","description":"City name, e.g. 'Москва'"}""",
        ),
        required = listOf("location"),
    )

    override suspend fun execute(arguments: String): String {
        val obj = ToolArgs.parse(arguments)
            ?: return JsonOut.error("Invalid JSON arguments")
        val location = obj.string("location")
            ?: return JsonOut.error("Missing required parameter: location")
        return try {
            weatherClient.getWeather(location)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Audit #4: barge-in cancellation must propagate (the client's
            // own rethrow in httpGet would otherwise be undone here).
            throw e
        } catch (e: Exception) {
            JsonOut.error("Weather lookup failed: ${e.message}")
        }
    }
}
