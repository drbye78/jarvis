package com.jarvis.assistant.weather

import com.jarvis.assistant.location.LocationOutcome
import com.jarvis.assistant.location.LocationResolver
import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.tools.ToolArgs
import com.jarvis.assistant.tools.ToolContract
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.int
import com.jarvis.assistant.tools.schema
import com.jarvis.assistant.tools.string
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.CancellationException

/**
 * Localized phrases the weather tool returns when the default location cannot
 * be resolved. The interface keeps this file Android-free; production passes
 * the `tool_weather_location_*` string resources.
 */
interface WeatherToolMessages {
    val locationDenied: String
    val locationUnavailable: String
}

/** RU fallback for non-Android callers and JVM tests. */
object DefaultWeatherToolMessages : WeatherToolMessages {
    override val locationDenied: String =
        "Не удалось определить местоположение: нет доступа. " +
            "Укажите город в настройках или разрешите доступ к местоположению."
    override val locationUnavailable: String =
        "Не удалось определить местоположение. Укажите город в настройках."
}

/**
 * LLM-facing `getWeather`. `location` is OPTIONAL: when omitted the resolver
 * supplies the configured place (or a bounded GPS fix), so a plain «какая
 * погода?» works. The result carries DATED daily entries, which is what makes
 * follow-ups («а завтра?») answerable from conversation history.
 */
class WeatherTool(
    private val weatherClient: WeatherClient,
    private val resolver: LocationResolver,
    private val messages: WeatherToolMessages = DefaultWeatherToolMessages,
    /**
     * Per-tool budget: GPS (≤6 s) + geocode + forecast can exceed the 15 s
     * registry default. Sourced from [com.jarvis.assistant.config.JarvisConfig.weatherToolTimeoutMs]
     * in production; the default mirrors it for non-Android callers.
     */
    private val budgetMs: Long = 20_000,
) : ToolContract {
    override val name = "getWeather"
    override val risk = ToolRisk.READ_ONLY
    override val description: String =
        "Get the current weather and a daily forecast (1-7 days) for a city or place. " +
            "The result contains dated daily entries; use them to answer follow-ups like " +
            "«а завтра?» or «а в Сочи?»."
    override val parametersJson = schema(
        mapOf(
            "location" to
                """{"type":"string","description":"City or place name, e.g. 'Москва' or 'Сочи'. """ +
                """OMIT to use the user's default location."}""",
            "days" to
                """{"type":"integer","minimum":1,"maximum":7,"description":"How many forecast """ +
                """days to return (1-7, default 7). Use 1 for a current-conditions-only question."}""",
        ),
        required = emptyList(),
    )

    /**
     * GPS (≤6 s) + geocode + forecast can exceed the 15 s registry default.
     * Value comes from [com.jarvis.assistant.config.JarvisConfig.weatherToolTimeoutMs].
     */
    override val timeoutMs: Long? = budgetMs

    override suspend fun execute(arguments: String): String {
        val obj = ToolArgs.parse(arguments)
            ?: return JsonOut.error("Invalid JSON arguments")
        val days = clampDays(obj.int("days"))
        val explicit = obj.string("location")?.trim()?.takeIf { it.isNotEmpty() }
        val location = explicit?.let { ResolvedLocation.Place(it) }
            ?: when (val outcome = resolver.resolve()) {
                is LocationOutcome.Resolved -> outcome.location
                LocationOutcome.PermissionDenied -> return JsonOut.error(messages.locationDenied)
                LocationOutcome.Unavailable -> return JsonOut.error(messages.locationUnavailable)
            }
        return try {
            weatherClient.getWeather(WeatherQuery(location, days))
        } catch (e: CancellationException) {
            // Barge-in cancellation must propagate (the client's
            // own rethrow in httpGet would otherwise be undone here).
            throw e
        } catch (e: Exception) {
            JsonOut.error("Weather lookup failed: ${e.message}")
        }
    }

    private fun clampDays(requested: Int?): Int =
        (requested ?: DEFAULT_FORECAST_DAYS).coerceIn(1, MAX_FORECAST_DAYS)
}
