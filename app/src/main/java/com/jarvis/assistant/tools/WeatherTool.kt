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
 */
class OpenMeteoWeatherClient(private val httpClient: OkHttpClient) : WeatherClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getWeather(location: String): String = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(location.trim(), "UTF-8")
        val geoUrl = ("https://geocoding-api.open-meteo.com/v1/search" +
            "?name=$encoded&count=1&language=ru").toHttpUrl()

        val geoBody = httpGet(geoUrl.toString())
            ?: return@withContext JsonOut.error("Weather service unreachable")
        val geoJson = runCatching { json.parseToJsonElement(geoBody).jsonObject }
            .getOrNull() ?: return@withContext JsonOut.error("Bad geocoding response")

        val results = geoJson["results"]?.jsonArray
        if (results.isNullOrEmpty()) {
            return@withContext JsonOut.error("Location not found: $location")
        }
        val first = results[0].jsonObject
        val lat = first["latitude"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext JsonOut.error("Could not resolve coordinates")
        val lon = first["longitude"]?.jsonPrimitive?.contentOrNull
            ?: return@withContext JsonOut.error("Could not resolve coordinates")
        val displayName = first["name"]?.jsonPrimitive?.contentOrNull ?: location

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
            put("temp", JsonPrimitive(temp ?: "н/д"))
            put("feels_like", JsonPrimitive(feels ?: "н/д"))
            put("wind_kmh", JsonPrimitive(wind ?: "н/д"))
            put("condition", JsonPrimitive(weatherCodeToRussian(code)))
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

    private fun weatherCodeToRussian(code: Int?): String = when (code) {
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
}

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
