package com.jarvis.assistant

import com.jarvis.assistant.tools.OpenMeteoWeatherClient
import com.jarvis.assistant.tools.WeatherClient
import com.jarvis.assistant.tools.WeatherTool
import com.jarvis.assistant.tools.WeatherToolMessages
import com.jarvis.assistant.tools.weather.LocationOutcome
import com.jarvis.assistant.tools.weather.WeatherLocation
import com.jarvis.assistant.tools.weather.WeatherLocationResolver
import com.jarvis.assistant.tools.weather.WeatherQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIXPLAN A6: locale-aware geocoding + exact-name disambiguation +
 * injected not-available placeholder.
 *
 * FORECAST lane: a [WeatherLocation.Place] is geocoded then forecast at those
 * coordinates; a [WeatherLocation.Coords] request skips geocoding entirely.
 * `daily` is column-oriented and MUST be paired by index, and the request MUST
 * pin `timezone=auto` (Open-Meteo otherwise shifts day boundaries to GMT).
 *
 * REAL-DATA NOTE: `api.open-meteo.com` is unreachable from the build sandbox,
 * so the forecast payloads below are built to the DOCUMENTED Open-Meteo
 * schema — they are NOT captured live responses. The geocoding shapes WERE
 * live-verified (`{"results":[…]}` / a bare `{"generationtime_ms":…}` for
 * not-found). Every request still goes to MockWebServer, never the internet.
 */
class WeatherClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        language: String = "en",
        conditionFor: (Int?) -> String = { "condition" },
        notAvailable: String = "N/A",
        forecastDays: Int = 7,
    ) = OpenMeteoWeatherClient(
        httpClient = OkHttpClient(),
        conditionFor = conditionFor,
        languageTag = language,
        notAvailable = notAvailable,
        forecastDays = forecastDays,
        // Hermetic (COGNITIVE_PLAN 0.6): the mock server receives every
        // request — never the live open-meteo endpoints.
        geoBaseUrl = server.url("/").toString().trimEnd('/'),
        forecastBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    /** Default condition mapper (RU) — for the WMO-coverage assertions. */
    private fun defaultClient(language: String = "ru") = OpenMeteoWeatherClient(
        httpClient = OkHttpClient(),
        languageTag = language,
        notAvailable = "н/д",
        geoBaseUrl = server.url("/").toString().trimEnd('/'),
        forecastBaseUrl = server.url("/").toString().trimEnd('/'),
    )

    private fun query(location: WeatherLocation, days: Int) = WeatherQuery(location, days)

    private fun enqueueGeoWithTwoCities() {
        // Two candidates: the exact requested name is SECOND (the old
        // first-result-only behavior would answer the wrong city).
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[
                     {"name":"Москва (US)","latitude":"1.0","longitude":"2.0","country":"United States"},
                     {"name":"Москва","latitude":"55.7","longitude":"37.6","country":"Россия"}
                   ]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"current":{"temperature_2m":"21.4","apparent_temperature":"20.0",
                     "weather_code":"0","wind_speed_10m":"3.2"}}""",
            ),
        )
    }

    @Test
    fun `exact-name candidate wins over the raw first hit`() = runBlocking {
        enqueueGeoWithTwoCities()
        val out = client().getWeather(query(WeatherLocation.Place("Москва"), 3))

        // Request order: geocoding, then the forecast for the SECOND
        // candidate's coords (the exact-name match).
        server.takeRequest() // geocoding
        val forecast = server.takeRequest()
        assertTrue("forecast hit wrong coords: ${forecast.path}", forecast.path!!.contains("latitude=55.7"))
        assertTrue(out.contains("\"location\":\"Москва\""))
        assertTrue(out.contains("\"country\":\"Россия\""))
    }

    @Test
    fun `geocoding url carries the injected language and count of 5`() {
        runBlocking {
            enqueueGeoWithTwoCities()
            client(language = "en").getWeather(query(WeatherLocation.Place("Москва"), 1))
            val geo = server.takeRequest()
            assertTrue(geo.path!!.contains("count=5"))
            assertTrue(geo.path!!.contains("language=en"))
            // Drain the queue so tearDown does not trip on pending responses.
            server.takeRequest()
        }
    }

    @Test
    fun `missing readings render through the injected placeholder`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"results":[{"name":"Berlin","latitude":"52.5","longitude":"13.4","country":"Germany"}]}""",
            ),
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"current":{"weather_code":"0"}}""", // no temp/feels/wind
            ),
        )
        val out = client(language = "en").getWeather(query(WeatherLocation.Place("Berlin"), 1))
        assertFalse(out.contains("н/д"))
        assertTrue(out.contains("N/A"))
    }

    // ------------------------------------------------------------------
    // Forecast lane
    // ------------------------------------------------------------------

    @Test
    fun `daily request pins timezone=auto and the requested day count`() = runBlocking {
        enqueueGeoWithTwoCities()
        client().getWeather(query(WeatherLocation.Place("Москва"), 3))

        server.takeRequest() // geocoding
        val forecast = server.takeRequest()
        val path = forecast.path!!
        assertTrue("timezone=auto is MANDATORY once daily= is requested", path.contains("timezone=auto"))
        assertTrue("the requested day count must be forwarded", path.contains("forecast_days=3"))
        assertTrue("the daily block must be requested", path.contains("daily="))
        assertTrue("current conditions must still be requested", path.contains("current="))
    }

    @Test
    fun `coords skip geocoding and use the label without a country`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(FORECAST_WITH_DAILY))
        val out = client().getWeather(
            query(WeatherLocation.Coords(43.59699, 39.72477, "текущее местоположение"), 2),
        )

        assertEquals("exactly one request: the forecast", 1, server.requestCount)
        val path = server.takeRequest().path!!
        assertTrue(path.contains("latitude=43.59699"))
        assertTrue(path.contains("forecast_days=2"))
        assertFalse("coords must not hit the geocoder", path.contains("/v1/search"))
        assertTrue(out.contains("\"location\":\"текущее местоположение\""))
        assertFalse("a GPS label has no country", out.contains("\"country\""))
    }

    @Test
    fun `place geocodes before forecasting`() = runBlocking {
        enqueueGeoWithTwoCities()
        client().getWeather(query(WeatherLocation.Place("Москва"), 1))

        assertEquals("geocoding + forecast", 2, server.requestCount)
        assertTrue(server.takeRequest().path!!.contains("/v1/search"))
    }

    @Test
    fun `daily columns stay paired when one column has a gap`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(DOCUMENTED_GAPPED_DAILY))

        val out = defaultClient().getWeather(query(WeatherLocation.Coords(1.0, 2.0, "lbl"), 3))
        val daily = Json.parseToJsonElement(out).jsonObject["daily"]!!.jsonArray
        assertEquals("three dated days survive a short column", 3, daily.size)
        fun row(i: Int) = daily[i].jsonObject

        // Dates stay in their own positions.
        assertEquals("2026-09-24", row(0)["date"]!!.jsonPrimitive.content)
        assertEquals("2026-09-25", row(1)["date"]!!.jsonPrimitive.content)
        assertEquals("2026-09-26", row(2)["date"]!!.jsonPrimitive.content)

        // Column `temperature_2m_min` has only TWO entries: day 3's min is
        // missing, yet day 3's max (index 2 of its own column) is intact —
        // a naive sequential/zip read would mispair these.
        assertEquals(26.1, row(0)["temp_max"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(19.0, row(1)["temp_min"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(20.5, row(2)["temp_max"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("н/д", row(2)["temp_min"]!!.jsonPrimitive.content)

        // Column `precipitation_sum` carries a null gap at index 1 only.
        assertEquals(0.0, row(0)["precipitation_mm"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals("н/д", row(1)["precipitation_mm"]!!.jsonPrimitive.content)
        assertEquals(2.5, row(2)["precipitation_mm"]!!.jsonPrimitive.content.toDouble(), 0.001)
        assertEquals(80, row(2)["precipitation_probability"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `weekday names follow the language locale`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(FORECAST_WITH_DAILY))
        val en = defaultClient(language = "en")
            .getWeather(query(WeatherLocation.Coords(1.0, 2.0, "lbl"), 3))

        server.enqueue(MockResponse().setResponseCode(200).setBody(FORECAST_WITH_DAILY))
        val ru = defaultClient(language = "ru")
            .getWeather(query(WeatherLocation.Coords(1.0, 2.0, "lbl"), 3))

        fun weekday(raw: String) =
            Json.parseToJsonElement(raw).jsonObject["daily"]!!.jsonArray[0]
                .jsonObject["weekday"]!!.jsonPrimitive.content
        // 2026-09-24 is a Thursday; the name is resolved per language, not hardcoded.
        assertEquals("Thursday", weekday(en))
        assertTrue("ru weekday, got '${weekday(ru)}'", weekday(ru).equals("четверг", ignoreCase = true))
    }

    @Test
    fun `geocoding not-found returns a structured error without crashing`() = runBlocking {
        // Open-Meteo returns HTTP 200 with NO `results` key for a miss.
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"generationtime_ms":0.11}"""))
        val out = client(language = "en").getWeather(query(WeatherLocation.Place("Атлантида"), 1))

        assertTrue(out.contains("\"error\""))
        assertTrue(out.contains("Location not found"))
        assertEquals("no forecast request after a failed lookup", 1, server.requestCount)
    }

    @Test
    fun `days are clamped to the 1-7 window`() = runBlocking {
        enqueueGeoWithTwoCities()
        client().getWeather(query(WeatherLocation.Place("Москва"), 99))

        server.takeRequest() // geocoding
        assertTrue(server.takeRequest().path!!.contains("forecast_days=7"))
    }

    @Test
    fun `a non-positive day count falls back to the configured default`() = runBlocking {
        enqueueGeoWithTwoCities()
        client(forecastDays = 5).getWeather(query(WeatherLocation.Place("Москва"), 0))

        server.takeRequest() // geocoding
        assertTrue(server.takeRequest().path!!.contains("forecast_days=5"))
    }

    @Test
    fun `wmo 97 maps to the hail thunderstorm group not the else bucket`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"current":{"weather_code":"97"}}"""))
        val out = defaultClient().getWeather(query(WeatherLocation.Coords(1.0, 2.0, "lbl"), 1))

        assertTrue("97 must map to «гроза с градом», got $out", out.contains("гроза с градом"))
        assertFalse("97 must not fall through to the else bucket", out.contains("\"condition\":\"облачно\""))
    }

    // ------------------------------------------------------------------
    // Tool schema + optional location (LLM-facing contract)
    // ------------------------------------------------------------------

    private class CapturingWeatherClient : WeatherClient {
        var lastQuery: WeatherQuery? = null
        override suspend fun getWeather(query: WeatherQuery): String {
            lastQuery = query
            return """{"ok":true}"""
        }
    }

    private class FixedResolver(private val outcome: LocationOutcome) : WeatherLocationResolver {
        override suspend fun resolve(): LocationOutcome = outcome
    }

    private val messages = object : WeatherToolMessages {
        override val locationDenied: String = "denied"
        override val locationUnavailable: String = "unavailable"
    }

    @Test
    fun `schema makes location optional and adds a bounded days field`() {
        val tool = WeatherTool(CapturingWeatherClient(), FixedResolver(LocationOutcome.Unavailable), messages)
        val schema = tool.parametersJson

        assertTrue("days must be advertised", schema.contains("\"days\""))
        assertTrue(schema.contains("\"minimum\":1"))
        assertTrue(schema.contains("\"maximum\":7"))
        assertFalse("location must no longer be required", schema.contains("\"required\""))
        assertTrue(schema.contains("OMIT to use the user's default location"))
    }

    @Test
    fun `omitting location resolves the default and never errors as missing`() = runBlocking {
        val client = CapturingWeatherClient()
        val tool = WeatherTool(
            client,
            FixedResolver(LocationOutcome.Resolved(WeatherLocation.Place("Казань"))),
            messages,
        )
        val out = tool.execute("""{"days":3}""")

        assertFalse("no «Missing required parameter» path anymore", out.contains("\"error\""))
        assertEquals(WeatherLocation.Place("Казань"), client.lastQuery!!.location)
        assertEquals(3, client.lastQuery!!.days)
    }

    @Test
    fun `explicit location wins over the resolver`() = runBlocking {
        val client = CapturingWeatherClient()
        val tool = WeatherTool(client, FixedResolver(LocationOutcome.PermissionDenied), messages)
        tool.execute("""{"location":"Сочи"}""")

        assertEquals(WeatherLocation.Place("Сочи"), client.lastQuery!!.location)
    }

    @Test
    fun `resolver outcomes map to the injected messages`() = runBlocking {
        val denied = WeatherTool(CapturingWeatherClient(), FixedResolver(LocationOutcome.PermissionDenied), messages)
        assertTrue(denied.execute("{}").contains("denied"))

        val unavailable = WeatherTool(CapturingWeatherClient(), FixedResolver(LocationOutcome.Unavailable), messages)
        assertTrue(unavailable.execute("{}").contains("unavailable"))
    }

    @Test
    fun `tool clamps days and tolerates string and float forms`() = runBlocking {
        val client = CapturingWeatherClient()
        val tool = WeatherTool(client, FixedResolver(LocationOutcome.Unavailable), messages)

        tool.execute("""{"location":"X","days":99}""")
        assertEquals(7, client.lastQuery!!.days)
        tool.execute("""{"location":"X","days":"3"}""")
        assertEquals(3, client.lastQuery!!.days)
        tool.execute("""{"location":"X","days":3.5}""")
        assertEquals(3, client.lastQuery!!.days)
        tool.execute("""{"location":"X","days":0}""")
        assertEquals("an explicit 0 clamps to the minimum", 1, client.lastQuery!!.days)
        tool.execute("""{"location":"X"}""")
        assertEquals("an omitted day count uses the default", 7, client.lastQuery!!.days)
    }

    private companion object {
        const val FORECAST_WITH_DAILY =
            """{"current":{"temperature_2m":"21.4","apparent_temperature":"20.0",
                 "relative_humidity_2m":"61","precipitation":"0.0","weather_code":"0",
                 "wind_speed_10m":"3.2","is_day":"1"},
                "daily":{"time":["2026-09-24","2026-09-25","2026-09-26"],
                 "weather_code":[0,3,61],
                 "temperature_2m_max":[26.1,27.0,20.5],
                 "temperature_2m_min":[18.2,19.0,15.5],
                 "precipitation_sum":[0.0,0.0,2.5],
                 "precipitation_probability_max":[5,10,80],
                 "wind_speed_10m_max":[14.4,12.0,20.0]}}"""

        /**
         * DOCUMENTED-schema payload with deliberate gaps: `temperature_2m_min`
         * is SHORT (2 of 3) and `precipitation_sum` has a NULL at index 1 —
         * both must degrade per-index, never shift day i onto day j.
         */
        const val DOCUMENTED_GAPPED_DAILY =
            """{"current":{"temperature_2m":"21.4","apparent_temperature":"20.0",
                 "relative_humidity_2m":"61","precipitation":"0.0","weather_code":"0",
                 "wind_speed_10m":"3.2","is_day":"1"},
                "daily":{"time":["2026-09-24","2026-09-25","2026-09-26"],
                 "weather_code":[0,3,61],
                 "temperature_2m_max":[26.1,27.0,20.5],
                 "temperature_2m_min":[18.2,19.0],
                 "precipitation_sum":[0.0,null,2.5],
                 "precipitation_probability_max":[5,10,80],
                 "wind_speed_10m_max":[14.4,12.0,20.0]}}"""
    }
}
