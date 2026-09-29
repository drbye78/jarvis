package com.jarvis.assistant.integration

import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.weather.ProjectEolWeatherClient
import com.jarvis.assistant.weather.StreamableHttpMcpClient
import com.jarvis.assistant.weather.WeatherQuery
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end check of the Project EOL provider against the LIVE MCP endpoint.
 *
 * Needs NO credentials (the service is free and keyless), but it DOES need
 * egress — so it is opt-in via `JARVIS_LIVE_NETWORK=1` and SKIPS otherwise.
 * That keeps the normal gate hermetic: `*LiveSmokeTest` is excluded from
 * `testDebugUnitTest` anyway, and this assumption is the second net for anyone
 * running the live tier without network.
 *
 * Run:
 * ```
 * JARVIS_LIVE_NETWORK=1 ./gradlew :app:testDebugUnitTest \
 *   --tests "com.jarvis.assistant.integration.ProjectEolLiveSmokeTest"
 * ```
 */
class ProjectEolLiveSmokeTest {

    private val enabled: Boolean
        get() = System.getenv("JARVIS_LIVE_NETWORK")?.trim() in setOf("1", "true", "yes")

    private fun client() = ProjectEolWeatherClient(
        mcp = StreamableHttpMcpClient(
            httpClient = OkHttpClient.Builder()
                .callTimeout(30, TimeUnit.SECONDS)
                .build(),
            endpointUrl = "https://weatherapi.projecteol.ru/mcp/",
        ),
        conditionFor = { code -> "code:$code" },
        notAvailable = "N/A",
    )

    @Test
    fun `a named city yields a dated daily forecast`() = runBlocking {
        Assume.assumeTrue("JARVIS_LIVE_NETWORK not set — skipping the live tier", enabled)

        val out = client().getWeather(WeatherQuery(ResolvedLocation.Place("Москва"), 3))
        val root = Json.parseToJsonElement(out).jsonObject

        assertFalse("live lookup failed: $out", out.contains("\"error\""))
        assertTrue("expected a resolved location, got $out", root["location"]!!.jsonPrimitive.content.isNotBlank())

        val daily = root["daily"]!!.jsonArray
        assertTrue("expected at least one daily row, got $out", daily.isNotEmpty())

        val first = daily[0].jsonObject
        assertTrue("a date must be present", first["date"]!!.jsonPrimitive.content.startsWith("20"))
        // A real reading, not the not-available placeholder.
        assertTrue(
            "temp_max must be numeric, got ${first["temp_max"]}",
            first["temp_max"]!!.jsonPrimitive.content.toDoubleOrNull() != null,
        )
        assertTrue(first["condition"]!!.jsonPrimitive.content.startsWith("code:"))
    }

    @Test
    fun `coordinates yield a forecast without any geocoding step`() = runBlocking {
        Assume.assumeTrue("JARVIS_LIVE_NETWORK not set — skipping the live tier", enabled)

        val out = client().getWeather(
            WeatherQuery(ResolvedLocation.Coords(55.75, 37.62, "test point"), 1),
        )

        assertFalse("live lookup failed: $out", out.contains("\"error\""))
        val root = Json.parseToJsonElement(out).jsonObject
        assertTrue(root["daily"]!!.jsonArray.isNotEmpty())
    }
}
