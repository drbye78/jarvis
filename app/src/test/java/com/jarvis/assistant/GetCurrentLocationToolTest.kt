package com.jarvis.assistant

import com.jarvis.assistant.geo.GeoError
import com.jarvis.assistant.geo.GeoPlace
import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.GeoResult
import com.jarvis.assistant.geo.GeoRoute
import com.jarvis.assistant.geo.GeoToolClient
import com.jarvis.assistant.geo.TravelMode
import com.jarvis.assistant.location.LocationOutcome
import com.jarvis.assistant.location.LocationResolver
import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.tools.DefaultGeoToolMessages
import com.jarvis.assistant.tools.GetCurrentLocationTool
import com.jarvis.assistant.tools.ToolRisk
import com.jarvis.assistant.tools.ToolRisks
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `getCurrentLocation`: the device-forcing tool behind «где я нахожусь?».
 *
 * It must IGNORE a configured city (it calls `resolveDevice`), NAME the fix
 * through MapKit reverse geocoding when possible, and fall back to the honest
 * unnamed label plus raw coordinates when the label is unavailable — never
 * invent a city, never lose the coordinates.
 */
class GetCurrentLocationToolTest {

    private val messages = DefaultGeoToolMessages

    private fun resolver(device: LocationOutcome): LocationResolver = object : LocationResolver {
        // resolve() deliberately returns a DIFFERENT place: a tool using the
        // default path would answer with a city instead of the device fix.
        override suspend fun resolve(): LocationOutcome =
            LocationOutcome.Resolved(ResolvedLocation.Place("Сочи"))

        override suspend fun resolveDevice(): LocationOutcome = device
    }

    private class FakeGeoClient(private val label: GeoResult<String>) : GeoToolClient {
        var labelPoint: GeoPoint? = null

        override suspend fun searchPlaces(
            query: String,
            near: GeoPoint?,
            limit: Int,
        ): GeoResult<List<GeoPlace>> = GeoResult.Ok(emptyList())

        override suspend fun resolveLabel(point: GeoPoint): GeoResult<String> {
            labelPoint = point
            return label
        }

        override suspend fun route(
            origin: GeoPoint,
            destination: GeoPoint,
            mode: TravelMode,
            alternatives: Int,
        ): GeoResult<List<GeoRoute>> = GeoResult.Ok(emptyList())
    }

    private fun tool(
        device: LocationOutcome,
        label: GeoResult<String> = GeoResult.Ok("улица Тверская, 1"),
        unnamed: String = "текущее местоположение",
    ) = GetCurrentLocationTool(resolver(device), FakeGeoClient(label), messages, unnamedLabel = { unnamed })

    private fun json(out: String) = Json.parseToJsonElement(out).jsonObject

    @Test
    fun `named fix returns the label and the raw coordinates`() = runBlocking {
        val coords = ResolvedLocation.Coords(55.75, 37.61, "текущее местоположение")
        val client = FakeGeoClient(GeoResult.Ok("Москва, Красная площадь"))
        val underTest = GetCurrentLocationTool(
            resolver(LocationOutcome.Resolved(coords)),
            client,
            messages,
            unnamedLabel = { "текущее местоположение" },
        )

        val out = json(underTest.execute("{}"))

        assertEquals("Москва, Красная площадь", out["label"]!!.jsonPrimitive.content)
        assertEquals(55.75, out["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(37.61, out["lon"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(GeoPoint(55.75, 37.61), client.labelPoint)
    }

    @Test
    fun `label failure falls back to the honest unnamed label but keeps coordinates`() = runBlocking {
        val coords = ResolvedLocation.Coords(55.75, 37.61, "текущее местоположение")
        val underTest = tool(
            LocationOutcome.Resolved(coords),
            label = GeoResult.Err(GeoError.NO_KEY),
            unnamed = "текущее местоположение",
        )

        val out = json(underTest.execute("{}"))

        assertEquals("текущее местоположение", out["label"]!!.jsonPrimitive.content)
        assertEquals(55.75, out["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(37.61, out["lon"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `key changed and not found also fall back without inventing a city`() = runBlocking {
        val coords = ResolvedLocation.Coords(55.75, 37.61, "текущее местоположение")
        listOf(GeoError.KEY_CHANGED, GeoError.NOT_FOUND, GeoError.FAILED).forEach { error ->
            val out = json(
                tool(LocationOutcome.Resolved(coords), label = GeoResult.Err(error)).execute("{}"),
            )
            assertEquals(
                "wrong fallback label for $error",
                "текущее местоположение",
                out["label"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun `permission denied returns the honest existing message`() = runBlocking {
        assertEquals(
            com.jarvis.assistant.util.JsonOut.error(messages.locationDenied),
            tool(LocationOutcome.PermissionDenied).execute("{}"),
        )
    }

    @Test
    fun `unavailable returns the honest existing message`() = runBlocking {
        assertEquals(
            com.jarvis.assistant.util.JsonOut.error(messages.locationUnavailable),
            tool(LocationOutcome.Unavailable).execute("{}"),
        )
    }

    @Test
    fun `tool uses the device resolver not the configured default`() = runBlocking {
        // resolve() returns Place("Сочи"); the output must still be the device
        // coords, proving resolveDevice (not resolve) drove the answer.
        val out = json(
            tool(LocationOutcome.Resolved(ResolvedLocation.Coords(1.0, 2.0, "текущее местоположение")))
                .execute("{}"),
        )
        assertEquals(1.0, out["lat"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `the tool is registered as READ_ONLY with a valid empty-object schema`() {
        val underTest = tool(LocationOutcome.Unavailable)
        assertEquals("getCurrentLocation", underTest.name)
        assertEquals(ToolRisk.READ_ONLY, underTest.risk)
        assertEquals(ToolRisk.READ_ONLY, ToolRisks.of("getCurrentLocation"))
        assertTrue(underTest.parametersJson.contains("\"type\":\"object\""))
        assertTrue(underTest.description.isNotBlank())
    }
}
