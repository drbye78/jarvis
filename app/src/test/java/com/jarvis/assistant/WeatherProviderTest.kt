package com.jarvis.assistant

import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.weather.SelectingWeatherClient
import com.jarvis.assistant.weather.WeatherClient
import com.jarvis.assistant.weather.WeatherOutcome
import com.jarvis.assistant.weather.WeatherProvider
import com.jarvis.assistant.weather.WeatherQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider selection: the stored id parses tolerantly, and the chosen provider
 * is read on EVERY call so a Settings change lands on the next weather turn
 * without a graph rebuild (the LIVE apply policy).
 */
class WeatherProviderTest {

    private class NamedClient(private val tag: String) : WeatherClient {
        override suspend fun getWeatherOutcome(query: WeatherQuery): WeatherOutcome =
            WeatherOutcome.Ok(tag)
    }

    private val query = WeatherQuery(ResolvedLocation.Place("Москва"), 1)

    @Test
    fun `ids round-trip and are unique`() {
        assertEquals(WeatherProvider.OPEN_METEO, WeatherProvider.fromId("open_meteo"))
        assertEquals(WeatherProvider.PROJECT_EOL, WeatherProvider.fromId("project_eol"))
        assertEquals(
            WeatherProvider.entries.size,
            WeatherProvider.entries.map { it.id }.toSet().size,
        )
    }

    @Test
    fun `the stored default and the parse fallback agree`() {
        // The AppPrefs literal and the enum fallback must not silently diverge.
        assertEquals(AppPrefs.DEFAULT_WEATHER_PROVIDER, WeatherProvider.DEFAULT.id)
        assertEquals(WeatherProvider.DEFAULT, WeatherProvider.fromId(null))
    }

    @Test
    fun `an unknown or blank id degrades to the default instead of throwing`() {
        // A stale/renamed/absent value must never crash a weather turn.
        // Project EOL is the default: Open-Meteo is DPI-blocked from Russia.
        assertEquals(WeatherProvider.PROJECT_EOL, WeatherProvider.fromId(null))
        assertEquals(WeatherProvider.PROJECT_EOL, WeatherProvider.fromId(""))
        assertEquals(WeatherProvider.PROJECT_EOL, WeatherProvider.fromId("   "))
        assertEquals(WeatherProvider.PROJECT_EOL, WeatherProvider.fromId("some_future_provider"))
    }

    @Test
    fun `the parse tolerates case and padding`() {
        assertEquals(WeatherProvider.PROJECT_EOL, WeatherProvider.fromId(" Project_EOL "))
    }

    @Test
    fun `the selected provider routes the query`() = runBlocking {
        val selecting = SelectingWeatherClient(
            providerFor = { WeatherProvider.PROJECT_EOL },
            openMeteo = NamedClient("open-meteo"),
            projectEol = NamedClient("project-eol"),
        )

        assertEquals("project-eol", selecting.getWeather(query))
    }

    @Test
    fun `a provider change applies to the next call without a rebuild`() = runBlocking {
        // Backs the LIVE apply policy: the client reads the pref per call.
        var active = WeatherProvider.OPEN_METEO
        val selecting = SelectingWeatherClient(
            providerFor = { active },
            openMeteo = NamedClient("open-meteo"),
            projectEol = NamedClient("project-eol"),
        )

        assertEquals("open-meteo", selecting.getWeather(query))
        active = WeatherProvider.PROJECT_EOL
        assertEquals("project-eol", selecting.getWeather(query))
    }
}
