package com.jarvis.assistant.weather

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.pow
import kotlin.math.round

/**
 * The ONE output contract every [WeatherClient] must produce, and the helpers
 * to build it.
 *
 * ```
 * { "location": <string>, ["country": <string>,] "current": <readings>,
 *   "hourly": [ <hour row>, ... ], "daily": [ <dated row>, ... ] }
 * readings = { temp, feels_like, condition, wind_kmh, humidity, precipitation }
 * hour row = { time (local HH:mm), temp, feels_like, condition,
 *              precipitation_mm, precipitation_probability, wind_kmh }
 * dated row = { date, weekday, condition, temp_max, temp_min,
 *               precipitation_mm, precipitation_probability, wind_max_kmh,
 *               feels_like_max, feels_like_min }
 * ```
 *
 * The LLM is told to rely on the DATED daily rows, which is what makes
 * follow-ups («а завтра?») answerable from conversation history. The `hourly`
 * array carries the NEXT ~12 hours (starting at the current hour) so questions
 * like «во сколько сегодня дождь?» / «погода через 3 часа» can be answered
 * from the same document. Providers MUST assemble rows BY INDEX (never by
 * zipping misaligned arrays) and render a missing reading through the caller's
 * not-available placeholder.
 *
 * A provider that cannot supply a reading (e.g. Project EOL publishes no
 * precipitation probability) OMITS the key rather than inventing a value or
 * widening the shape with a placeholder — the model reads what is present.
 * [apparentTemperatureC] is the ONE exception: a DERIVED estimate, not a server
 * reading, used where the upstream has no apparent temperature.
 */
object WeatherContract {
    const val CURRENT = "current"
    const val HOURLY = "hourly"
    const val DAILY = "daily"
    const val LOCATION = "location"
    const val COUNTRY = "country"

    const val DATE = "date"
    const val WEEKDAY = "weekday"
    const val TIME = "time"
    const val CONDITION = "condition"
    const val TEMP_MAX = "temp_max"
    const val TEMP_MIN = "temp_min"
    const val FEELS_LIKE_MAX = "feels_like_max"
    const val FEELS_LIKE_MIN = "feels_like_min"
    const val PRECIPITATION_MM = "precipitation_mm"
    const val PRECIPITATION_PROBABILITY = "precipitation_probability"
    const val WIND_MAX_KMH = "wind_max_kmh"

    const val TEMP = "temp"
    const val FEELS_LIKE = "feels_like"
    const val WIND_KMH = "wind_kmh"
    const val HUMIDITY = "humidity"
    const val PRECIPITATION = "precipitation"
}

/**
 * A DERIVED *estimate* of the apparent ("feels like") temperature in °C — NOT
 * a server reading, and never labelled as one. Used when the upstream provides
 * no apparent temperature (Project EOL).
 *
 * Regimes:
 *  - [tempC] below 10 °C with wind above 4.8 km/h → Environment-Canada WIND
 *    CHILL `13.12 + 0.6215*T - 11.37*V^0.16 + 0.3965*T*V^0.16` (V in km/h).
 *  - [tempC] above 27 °C with humidity present → Rothfusz HEAT INDEX
 *    (Fahrenheit regression converted back to °C).
 *  - otherwise the air temperature itself (no correction).
 *
 * Returns null when [tempC] is null. The result is rounded to one decimal.
 */
internal fun apparentTemperatureC(tempC: Double?, windKmh: Double?, humidityPct: Double?): Double? {
    if (tempC == null) return null
    val apparent = when {
        tempC <= 10.0 && (windKmh ?: 0.0) > WIND_CHILL_MIN_KMH -> {
            val v = windKmh!!.pow(0.16)
            13.12 + 0.6215 * tempC - 11.37 * v + 0.3965 * tempC * v
        }
        tempC >= HEAT_INDEX_MIN_C && humidityPct != null -> heatIndexC(tempC, humidityPct)
        else -> tempC
    }
    return round(apparent * 10) / 10
}

/** Rothfusz regression — T in °F, humidity in %, result converted back to °C. */
private fun heatIndexC(tempC: Double, humidityPct: Double): Double {
    val t = tempC * 9.0 / 5.0 + 32.0
    val r = humidityPct
    val hi = -42.379 + 2.04901523 * t + 10.14333127 * r -
        0.22475541 * t * r - 0.00683783 * t * t -
        0.05481717 * r * r + 0.00122874 * t * t * r +
        0.00085282 * t * r * r - 0.00000199 * t * t * r * r
    return (hi - 32.0) * 5.0 / 9.0
}

/** Below this wind the wind-chill term adds nothing, so it is not applied. */
private const val WIND_CHILL_MIN_KMH = 4.8

/** At or above this air temperature the heat-index regime applies. */
private const val HEAT_INDEX_MIN_C = 27.0

/** Kelvin → degrees Celsius (Project EOL publishes air temperature in K). */
internal fun kelvinToCelsius(kelvin: Double): Double = kelvin - 273.15

/** Metres per second → kilometres per hour (Project EOL publishes wind in m/s). */
internal fun metresPerSecondToKmh(metresPerSecond: Double): Double = metresPerSecond * 3.6

/**
 * Derives a WMO weather code from Project EOL's physical parameters.
 *
 * Project EOL is a raw GFS feed: it has NO weather-code equivalent, so the
 * condition has to be inferred. The mapping is deliberately coarse (the same
 * 15 buckets [OpenMeteoWeatherClient] receives from WMO) and its ONLY job is
 * to pick a spoken phrase — so the thresholds are named and conservative
 * rather than tuned.
 *
 * Precedence: precipitation (amount decides light/moderate/heavy, temperature
 * decides rain/snow) beats cloud cover. Cloud area fraction is 0..1.
 */
internal fun deriveWmoCode(
    precipitationMm: Double?,
    temperatureC: Double?,
    cloudFraction: Double?,
): Int {
    val precip = precipitationMm ?: 0.0
    val freezing = (temperatureC ?: 10.0) <= 0.5
    if (precip > LIGHT_PRECIP_MM) {
        return when {
            freezing -> if (precip >= HEAVY_PRECIP_MM) 75 else if (precip >= MODERATE_PRECIP_MM) 73 else 71
            precip >= HEAVY_PRECIP_MM -> 65
            precip >= MODERATE_PRECIP_MM -> 63
            else -> 61
        }
    }
    val cloud = cloudFraction
    return when {
        cloud == null -> 3
        cloud < CLEAR_MAX_FRACTION -> 0
        cloud < CLOUDY_MIN_FRACTION -> 2
        else -> 3
    }
}

/** Below this the "trace" accumulation is noise, not reported weather. */
private const val LIGHT_PRECIP_MM = 0.05
private const val MODERATE_PRECIP_MM = 1.0
private const val HEAVY_PRECIP_MM = 4.0
private const val CLEAR_MAX_FRACTION = 0.35
private const val CLOUDY_MIN_FRACTION = 0.75

/** Convenience for providers: a `{ "location": … }`-rooted document builder. */
internal fun weatherDocument(
    displayName: String,
    country: String?,
    current: JsonObject,
    daily: List<JsonObject>,
    hourly: List<JsonObject> = emptyList(),
): String = buildJsonObject {
    put(WeatherContract.LOCATION, JsonPrimitive(displayName))
    if (!country.isNullOrBlank()) put(WeatherContract.COUNTRY, JsonPrimitive(country))
    put(WeatherContract.CURRENT, current)
    put(WeatherContract.HOURLY, buildJsonArray { hourly.forEach { add(it) } })
    put(WeatherContract.DAILY, buildJsonArray { daily.forEach { add(it) } })
}.toString()

/** Reads `a.b.c` style dotted parameter lookups out of a Project EOL `values` object. */
internal fun JsonObject.value(name: String): Double? =
    this[name]?.let { entry ->
        runCatching { entry.jsonObject["value"]?.jsonPrimitive?.doubleOrNull }.getOrNull()
    }
