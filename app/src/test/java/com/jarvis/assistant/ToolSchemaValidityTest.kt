package com.jarvis.assistant

import com.jarvis.assistant.geo.GeoPlace
import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.GeoResult
import com.jarvis.assistant.geo.GeoRoute
import com.jarvis.assistant.geo.GeoToolClient
import com.jarvis.assistant.geo.TravelMode
import com.jarvis.assistant.location.LocationOutcome
import com.jarvis.assistant.location.LocationResolver
import com.jarvis.assistant.tools.DefaultGeoToolMessages
import com.jarvis.assistant.tools.EMPTY_PARAMETER_SCHEMA
import com.jarvis.assistant.tools.GeoPlaceTool
import com.jarvis.assistant.tools.GeoRouteTool
import com.jarvis.assistant.tools.schema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every tool builds its `parametersJson` through the shared [schema] helper, so
 * a regression here would silently break a tool's advertised parameter surface.
 *
 * `findPlace` shipped exactly that: an unescaped `"` in a description produced
 * invalid JSON, the registry caught the parse failure and substituted an empty
 * parameter object, and the LLM lost the `query` argument on every turn — with
 * the only symptom a per-turn ERROR log. These tests pin both the helper's
 * contract and the real geo tools' schemas.
 */
class ToolSchemaValidityTest {

    private class NoopGeoClient : GeoToolClient {
        override suspend fun searchPlaces(query: String, near: GeoPoint?, limit: Int): GeoResult<List<GeoPlace>> =
            GeoResult.Ok(emptyList())

        override suspend fun resolveLabel(point: GeoPoint): GeoResult<String> = GeoResult.Ok("")

        override suspend fun route(
            origin: GeoPoint,
            destination: GeoPoint,
            mode: TravelMode,
            alternatives: Int,
        ): GeoResult<List<GeoRoute>> = GeoResult.Ok(emptyList())
    }

    private val noLocation = object : LocationResolver {
        override suspend fun resolve(): LocationOutcome = LocationOutcome.Unavailable

        override suspend fun resolveDevice(): LocationOutcome = LocationOutcome.Unavailable
    }

    private fun propertiesOf(json: String): Set<String> =
        Json.parseToJsonElement(json).jsonObject["properties"]!!.jsonObject.keys

    @Test
    fun `findPlace ships a parseable schema exposing query and near`() {
        val tool = GeoPlaceTool(NoopGeoClient(), noLocation, DefaultGeoToolMessages)
        // The bug: this was invalid JSON, so the registry degraded it to {}.
        assertEquals("the schema must not degrade", true, propertiesOf(tool.parametersJson).isNotEmpty())
        assertTrue(propertiesOf(tool.parametersJson).containsAll(listOf("query", "near")))
    }

    @Test
    fun `getRoute ships a parseable schema exposing destination origin and mode`() {
        val tool = GeoRouteTool(NoopGeoClient(), noLocation, DefaultGeoToolMessages)
        assertTrue(
            propertiesOf(tool.parametersJson)
                .containsAll(listOf("destination", "origin", "mode")),
        )
    }

    @Test
    fun `getCurrentLocation ships a parseable no-argument schema`() {
        val tool = com.jarvis.assistant.tools.GetCurrentLocationTool(
            noLocation,
            NoopGeoClient(),
            DefaultGeoToolMessages,
            unnamedLabel = { "текущее местоположение" },
        )
        val parsed = Json.parseToJsonElement(tool.parametersJson).jsonObject
        assertEquals("object", (parsed["type"] as? JsonPrimitive)?.content)
        assertTrue(parsed["properties"]!!.jsonObject.isEmpty())
    }

    @Test
    fun `schema returns empty schema when a fragment contains an unescaped quote`() {
        val malformed = mapOf(
            "query" to """{"type":"string","description":"an organization, address or "place"}""",
        )
        assertEquals(EMPTY_PARAMETER_SCHEMA, schema(malformed))
    }

    @Test
    fun `schema output always parses for well-formed fragments`() {
        val json = schema(
            mapOf(
                "query" to """{"type":"string","description":"Что найти"}""",
                "near" to """{"type":"string","description":"Город"}""",
            ),
            required = listOf("query"),
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(setOf("query", "near"), parsed["properties"]!!.jsonObject.keys)
        assertEquals("""["query"]""", parsed["required"].toString())
    }
}
