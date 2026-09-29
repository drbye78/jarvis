package com.jarvis.assistant

import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.weather.McpToolClient
import com.jarvis.assistant.weather.McpToolResult
import com.jarvis.assistant.weather.ProjectEolWeatherClient
import com.jarvis.assistant.weather.WeatherQuery
import com.jarvis.assistant.weather.deriveWmoCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * Project EOL mapping: an MCP `tools/call` transport carrying an HOURLY NOAA
 * GFS series in physical units, collapsed into the app's daily/current
 * contract.
 *
 * The transport is faked so these tests isolate the mapping — conversion
 * (K→°C, m/s→km/h), per-day aggregation in the DEVICE time zone, derived
 * conditions, geocoding by `search_locations`, and the honest failure paths.
 * The payload shapes mirror LIVE responses captured from
 * `https://weatherapi.projecteol.ru/mcp/` (the live end-to-end check is
 * `integration/ProjectEolLiveSmokeTest`, which self-skips without an opt-in).
 */
class ProjectEolWeatherClientTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val moscow = ZoneId.of("Europe/Moscow")

    /** Records calls and replays canned tool payloads. */
    private class FakeMcp(
        var search: McpToolResult? = null,
        var forecast: McpToolResult? = null,
    ) : McpToolClient {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun callTool(name: String, argumentsJson: String): McpToolResult? {
            calls += name to argumentsJson
            return when {
                name.contains("search_locations") -> search
                name.contains("forecast") -> forecast
                else -> null
            }
        }
    }

    private fun client(mcp: McpToolClient, now: String = "2026-09-29T19:00:00Z") =
        ProjectEolWeatherClient(
            mcp = mcp,
            conditionFor = { code -> "code:$code" },
            notAvailable = "N/A",
            forecastDays = 7,
            nowMs = { Instant.parse(now).toEpochMilli() },
            zone = { moscow },
        )

    /** One forecast hour: 283.15 K (10 °C), 5 m/s (18 km/h), 2 mm, cloud 1.0. */
    private fun hour(time: String, tempK: Double, precip: Double, windMs: Double, cloud: Double, humidity: Double) =
        """{"time":"$time","values":{
             "surface.air_temperature_2m":{"value":$tempK,"unit":"K"},
             "surface.precipitation_amount":{"value":$precip,"unit":"kg m-2"},
             "surface.cloud_area_fraction":{"value":$cloud,"unit":"1"},
             "surface.relative_humidity_2m":{"value":$humidity,"unit":"%"},
             "wind_speed_10m":{"value":$windMs,"unit":"m s-1","derived":true}}}"""

    private fun forecast(vararg hours: String) =
        McpToolResult("""{"forecast":[${hours.joinToString(",")}]}""", isError = false)

    private fun searchResults(vararg places: Triple<String, Double, Double>) =
        McpToolResult(
            """{"query":"q","results":[${places.joinToString(",") { (name, lat, lon) ->
                """{"name":"$name","country":"RU","latitude":$lat,"longitude":$lon}"""
            }}]}""",
            isError = false,
        )

    // ---- the current hour ------------------------------------------------

    @Test
    fun `current readings convert Kelvin to Celsius and m per s to km per h`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Москва", 55.75, 37.61)),
            forecast = forecast(hour("2026-09-29T20:00:00Z", 283.15, 2.0, 5.0, 1.0, 80.0)),
        )

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Москва"), 1))
        val current = json.parseToJsonElement(out).jsonObject["current"]!!.jsonObject

        assertEquals(10.0, current["temp"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(18.0, current["wind_kmh"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(80, current["humidity"]!!.jsonPrimitive.content.toInt())
        // 2 mm at +10 °C is moderate rain.
        assertEquals("code:63", current["condition"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the current hour is the first not in the past, not just the first row`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Москва", 55.75, 37.61)),
            forecast = forecast(
                hour("2026-09-29T18:00:00Z", 273.15, 0.0, 1.0, 0.0, 50.0),
                hour("2026-09-29T20:00:00Z", 283.15, 0.0, 1.0, 0.0, 50.0),
            ),
        )

        // now = 19:00Z → the 20:00 row (10 °C), not the 18:00 one (0 °C).
        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Москва"), 1))
        val current = json.parseToJsonElement(out).jsonObject["current"]!!.jsonObject

        assertEquals(10.0, current["temp"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    // ---- daily aggregation ----------------------------------------------

    @Test
    fun `daily rows aggregate the hourly series per LOCAL day`() = runBlocking {
        // 20:00Z = 23:00 MSK (29th); 22:00Z = 01:00 MSK (30th) — two local days.
        val mcp = FakeMcp(
            search = searchResults(Triple("Москва", 55.75, 37.61)),
            forecast = forecast(
                hour("2026-09-29T20:00:00Z", 283.15, 2.0, 5.0, 1.0, 80.0),
                hour("2026-09-29T22:00:00Z", 278.15, 0.0, 2.0, 0.1, 60.0),
            ),
        )

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Москва"), 2))
        val daily = json.parseToJsonElement(out).jsonObject["daily"]!!.jsonArray

        assertEquals(2, daily.size)
        val first = daily[0].jsonObject
        assertEquals("2026-09-29", first["date"]!!.jsonPrimitive.content)
        assertEquals(10.0, first["temp_max"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(2.0, first["precipitation_mm"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(18.0, first["wind_max_kmh"]!!.jsonPrimitive.content.toDouble(), 0.001)
        // The daily condition is derived from the day's own precip/cloud.
        assertEquals("code:63", first["condition"]!!.jsonPrimitive.content)

        val second = daily[1].jsonObject
        assertEquals("2026-09-30", second["date"]!!.jsonPrimitive.content)
        assertEquals(5.0, second["temp_max"]!!.jsonPrimitive.content.toDouble(), 0.001)
        // Cloud 0.1 → clear.
        assertEquals("code:0", second["condition"]!!.jsonPrimitive.content)
        assertTrue(
            "weekday must be rendered",
            second["weekday"]!!.jsonPrimitive.content.isNotBlank(),
        )
    }

    @Test
    fun `precipitation accumulates across a day's hours`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Москва", 55.75, 37.61)),
            forecast = forecast(
                hour("2026-09-29T19:00:00Z", 283.15, 1.5, 1.0, 1.0, 80.0),
                hour("2026-09-29T20:00:00Z", 283.15, 2.5, 1.0, 1.0, 80.0),
            ),
        )

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Москва"), 1))
        val row = json.parseToJsonElement(out).jsonObject["daily"]!!.jsonArray[0].jsonObject

        assertEquals(4.0, row["precipitation_mm"]!!.jsonPrimitive.content.toDouble(), 0.001)
        // 4 mm is heavy rain.
        assertEquals("code:65", row["condition"]!!.jsonPrimitive.content)
    }

    // ---- geocoding -------------------------------------------------------

    @Test
    fun `an exact place name beats the first search hit`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(
                Triple("Москва (US)", 1.0, 2.0),
                Triple("Москва", 55.75, 37.61),
            ),
            forecast = forecast(hour("2026-09-29T20:00:00Z", 283.15, 0.0, 1.0, 0.0, 50.0)),
        )

        client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Москва"), 1))

        val args = json.parseToJsonElement(
            mcp.calls.last { it.first.contains("forecast") }.second,
        ).jsonObject
        // The SECOND candidate's coordinates (55.75/37.61), not the first's.
        assertEquals(55.75, args["latitude"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(37.61, args["longitude"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun `the configured country and resolved name are reported`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Moscow", 55.75, 37.61)),
            forecast = forecast(hour("2026-09-29T20:00:00Z", 283.15, 0.0, 1.0, 0.0, 50.0)),
        )

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Moscow"), 1))
        val root = json.parseToJsonElement(out).jsonObject

        assertEquals("Moscow", root["location"]!!.jsonPrimitive.content)
        assertEquals("RU", root["country"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GPS coordinates skip geocoding entirely`() = runBlocking {
        val mcp = FakeMcp(
            forecast = forecast(hour("2026-09-29T20:00:00Z", 283.15, 0.0, 1.0, 0.0, 50.0)),
        )

        val out = client(mcp).getWeather(
            WeatherQuery(ResolvedLocation.Coords(55.75, 37.61, "текущее местоположение"), 1),
        )

        assertEquals(1, mcp.calls.size)
        assertTrue(mcp.calls.none { it.first.contains("search_locations") })
        assertEquals(
            "текущее местоположение",
            json.parseToJsonElement(out).jsonObject["location"]!!.jsonPrimitive.content,
        )
    }

    // ---- request shape ---------------------------------------------------

    @Test
    fun `the request asks for local midnight onwards, in hours`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Moscow", 55.75, 37.61)),
            forecast = forecast(hour("2026-09-29T20:00:00Z", 283.15, 0.0, 1.0, 0.0, 50.0)),
        )

        client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Moscow"), 3))

        val args = json.parseToJsonElement(
            mcp.calls.last { it.first.contains("forecast") }.second,
        ).jsonObject
        // 2026-09-29 00:00 MSK == 2026-09-28T21:00:00Z, and 3 days == 72 hours.
        assertEquals("2026-09-28T21:00:00Z", args["start"]!!.jsonPrimitive.content)
        assertEquals(72, args["hours"]!!.jsonPrimitive.content.toInt())
        val params = args["parameters"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(params.contains("surface.air_temperature_2m"))
        assertTrue(params.contains("wind_speed_10m"))
    }

    // ---- failure paths ---------------------------------------------------

    @Test
    fun `an unknown place is reported honestly`() = runBlocking {
        val mcp = FakeMcp(search = McpToolResult("""{"query":"Аптека","results":[]}""", isError = false))

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Аптека"), 1))

        assertTrue(out.contains("Location not found"))
    }

    @Test
    fun `a tool-level error degrades to a typed message`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Moscow", 55.75, 37.61)),
            forecast = McpToolResult("""{"error":"hours must be 1 to 168"}""", isError = true),
        )

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Moscow"), 1))

        assertTrue(out.contains("Weather service error"))
    }

    @Test
    fun `an unreachable endpoint degrades honestly`() = runBlocking {
        val out = client(FakeMcp(search = null)).getWeather(WeatherQuery(ResolvedLocation.Place("Moscow"), 1))

        assertTrue(out.contains("Weather service unreachable"))
    }

    @Test
    fun `an empty forecast is not reported as a silent success`() = runBlocking {
        val mcp = FakeMcp(
            search = searchResults(Triple("Moscow", 55.75, 37.61)),
            forecast = McpToolResult("""{"forecast":[]}""", isError = false),
        )

        val out = client(mcp).getWeather(WeatherQuery(ResolvedLocation.Place("Moscow"), 1))

        assertTrue(out.contains("No forecast data"))
        assertFalse(out.contains("\"daily\""))
    }

    // ---- condition derivation -------------------------------------------

    @Test
    fun `the derived condition chooses rain or snow by temperature`() = runBlocking {
        // Moderate precipitation: rain above freezing, snow below.
        assertEquals(63, deriveWmoCode(precipitationMm = 2.0, temperatureC = 5.0, cloudFraction = 1.0))
        assertEquals(73, deriveWmoCode(precipitationMm = 2.0, temperatureC = -5.0, cloudFraction = 1.0))
    }

    @Test
    fun `the derived condition falls back to cloud cover when it is dry`() {
        assertEquals(0, deriveWmoCode(precipitationMm = 0.0, temperatureC = 20.0, cloudFraction = 0.1))
        assertEquals(2, deriveWmoCode(precipitationMm = 0.0, temperatureC = 20.0, cloudFraction = 0.5))
        assertEquals(3, deriveWmoCode(precipitationMm = 0.0, temperatureC = 20.0, cloudFraction = 0.9))
    }

    @Test
    fun `trace precipitation is not reported as weather`() {
        // Below the light threshold: still clear, not "rain".
        assertEquals(0, deriveWmoCode(precipitationMm = 0.01, temperatureC = 20.0, cloudFraction = 0.0))
    }
}
