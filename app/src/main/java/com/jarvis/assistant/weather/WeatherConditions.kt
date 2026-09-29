package com.jarvis.assistant.weather

/**
 * Weather-code → spoken condition name.
 *
 * The codes are the WMO set Open-Meteo returns natively. Project EOL publishes
 * no code at all, so [deriveWmoCode] infers one into the same vocabulary;
 * keeping a single mapping means both providers speak identically and a
 * provider swap cannot change a phrase.
 */

/** The original RU condition names — the non-Android default. */
fun weatherCodeToRussianDefault(code: Int?): String = when (code) {
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
    96, 97, 99 -> "гроза с градом"
    else -> "облачно"
}

/**
 * Locale-aware weather-code → condition-name resolver backed by the
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
            96, 97, 99 -> com.jarvis.assistant.R.string.weather_thunderstorm_hail
            else -> com.jarvis.assistant.R.string.weather_cloudy
        },
    )
