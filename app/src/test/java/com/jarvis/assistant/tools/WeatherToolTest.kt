package com.jarvis.assistant.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeWeatherClient : WeatherClient {
    override suspend fun getWeather(location: String, units: String): String {
        return """{"location":"$location","units":"$units","temp":21,"condition":"ясно"}"""
    }
}

class WeatherToolTest {
    private val tool = WeatherTool(FakeWeatherClient())

    @Test fun `valid location`() = runBlocking {
        val result = tool.execute("""{"location":"Moscow"}""")
        assertTrue(result.contains("Moscow"))
        assertTrue(result.contains("\"temp\""))
    }

    @Test fun `missing location`() = runBlocking {
        val result = tool.execute("""{}""")
        assertTrue(result.contains("\"error\""))
    }
}