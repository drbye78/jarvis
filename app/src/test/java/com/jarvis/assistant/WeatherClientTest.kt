package com.jarvis.assistant

import com.jarvis.assistant.tools.OpenMeteoWeatherClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * FIXPLAN A6: locale-aware geocoding + exact-name disambiguation +
 * injected not-available placeholder.
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

    private fun client(language: String = "en") = OpenMeteoWeatherClient(
        httpClient = OkHttpClient(),
        conditionFor = { "condition" },
        languageTag = language,
        notAvailable = "N/A",
        // Hermetic (COGNITIVE_PLAN 0.6): the mock server receives every
        // request — never the live open-meteo endpoints.
        geoBaseUrl = server.url("/").toString().trimEnd('/'),
        forecastBaseUrl = server.url("/").toString().trimEnd('/'),
    )

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
        val out = client().getWeather("Москва")

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
            client(language = "en").getWeather("Москва")
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
        val out = client(language = "en").getWeather("Berlin")
        assertFalse(out.contains("н/д"))
        assertTrue(out.contains("N/A"))
    }
}
