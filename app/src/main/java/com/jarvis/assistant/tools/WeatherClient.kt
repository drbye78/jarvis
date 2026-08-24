package com.jarvis.assistant.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

interface WeatherClient {
    suspend fun getWeather(location: String, units: String): String
}

class OpenMeteoWeatherClient(private val httpClient: OkHttpClient) : WeatherClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getWeather(location: String, units: String): String = withContext(Dispatchers.IO) {
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=$location&count=1&language=ru"
        val geoReq = Request.Builder().url(geoUrl).build()
        val geoResp = httpClient.newCall(geoReq).execute()
        val geoBody = geoResp.body?.string().orEmpty()
        val geoJson = json.parseToJsonElement(geoBody).jsonObject
        val results = geoJson["results"]?.jsonArray
        if (results.isNullOrEmpty()) return@withContext """{"error":"Location not found: $location"}"""
        val first = results[0].jsonObject
        val lat = first["latitude"]?.jsonPrimitive?.contentOrNull
        val lon = first["longitude"]?.jsonPrimitive?.contentOrNull
        val displayName = first["name"]?.jsonPrimitive?.contentOrNull ?: location
        if (lat == null || lon == null) return@withContext """{"error":"Could not resolve coordinates for $location"}"""

        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto"
        val weatherReq = Request.Builder().url(weatherUrl).build()
        val weatherResp = httpClient.newCall(weatherReq).execute()
        val weatherBody = weatherResp.body?.string().orEmpty()
        val weatherJson = json.parseToJsonElement(weatherBody).jsonObject
        val current = weatherJson["current"]?.jsonObject
        val temp = current?.get("temperature_2m")?.jsonPrimitive?.contentOrNull ?: "?"
        val code = current?.get("weather_code")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val condition = weatherCodeToRussian(code)

        """{"location":"$displayName","units":"$units","temp":$temp,"condition":"$condition"}"""
    }

    private fun weatherCodeToRussian(code: Int): String = when (code) {
        0 -> "ясно"
        1, 2, 3 -> "переменная облачность"
        45, 48 -> "туман"
        51, 53, 55 -> "морось"
        61, 63, 65 -> "дождь"
        71, 73, 75 -> "снег"
        80, 81, 82 -> "ливень"
        95, 96, 99 -> "гроза"
        else -> "облачно"
    }
}