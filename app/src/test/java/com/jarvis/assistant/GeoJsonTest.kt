package com.jarvis.assistant

import com.jarvis.assistant.geo.GeoJson
import com.jarvis.assistant.geo.GeoLeg
import com.jarvis.assistant.geo.GeoPlace
import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.GeoRoute
import com.jarvis.assistant.geo.TravelMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the exact JSON the LLM sees. The load-bearing property is TYPES: a
 * quoted "55.76" would still parse as JSON but the model could not compare or
 * route on it, so every coordinate/duration/transfer assertion checks both the
 * value AND that it is a JSON number.
 */
class GeoJsonTest {

    private val json = Json

    @Test
    fun `places emit numeric coordinates and the optional fields`() {
        val out = GeoJson.places(
            listOf(GeoPlace("Метро Тверская", "ул. Тверская, 1", GeoPoint(55.764, 37.605), "https://yandex.ru/maps/1")),
        )
        val place = json.parseToJsonElement(out).jsonObject["places"]!!.jsonArray.single().jsonObject

        assertEquals("Метро Тверская", place["name"]!!.jsonPrimitive.content)
        assertEquals("ул. Тверская, 1", place["address"]!!.jsonPrimitive.content)
        assertEquals(55.764, place["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(37.605, place["lon"]!!.jsonPrimitive.double, 1e-9)
        assertTrue("lat must be a JSON number", !place["lat"]!!.jsonPrimitive.isString)
        assertTrue("lon must be a JSON number", !place["lon"]!!.jsonPrimitive.isString)
        assertEquals("https://yandex.ru/maps/1", place["uri"]!!.jsonPrimitive.content)
    }

    @Test
    fun `absent place address and uri are explicit json null`() {
        val out = GeoJson.places(listOf(GeoPlace("Дом", null, GeoPoint(1.0, 2.0), null)))
        val place = json.parseToJsonElement(out).jsonObject["places"]!!.jsonArray.single().jsonObject

        assertTrue(place["address"] is JsonNull)
        assertTrue(place["uri"] is JsonNull)
    }

    @Test
    fun `transit route emits numeric duration_min transfers and the leg kinds`() {
        val route = GeoRoute(
            durationText = "42 мин",
            durationSeconds = 2520.0,
            transfers = 3,
            arrivalText = "14:35",
            walkingDistanceText = "800 м",
            legs = listOf(
                GeoLeg.Walk("5 мин"),
                GeoLeg.Transport("Автобус 12", "bus", 7, "20 мин"),
                GeoLeg.Transfer("Метро Тверская", null),
            ),
        )
        val out = GeoJson.route(GeoPoint(55.0, 37.0), GeoPoint(55.1, 37.1), TravelMode.TRANSIT, listOf(route))
        val root = json.parseToJsonElement(out).jsonObject

        assertEquals("transit", root["mode"]!!.jsonPrimitive.content)
        assertEquals(55.0, root["origin"]!!.jsonObject["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(37.1, root["destination"]!!.jsonObject["lon"]!!.jsonPrimitive.double, 1e-9)

        val r = root["routes"]!!.jsonArray.single().jsonObject
        assertEquals(42, r["duration_min"]!!.jsonPrimitive.int)
        assertEquals(3, r["transfers"]!!.jsonPrimitive.int)
        assertTrue("duration_min must be a JSON number", !r["duration_min"]!!.jsonPrimitive.isString)
        assertTrue("transfers must be a JSON number", !r["transfers"]!!.jsonPrimitive.isString)
        assertEquals("42 мин", r["duration_text"]!!.jsonPrimitive.content)
        assertEquals("14:35", r["arrival_text"]!!.jsonPrimitive.content)
        assertEquals("800 м", r["walking_distance_text"]!!.jsonPrimitive.content)

        val legs = r["legs"]!!.jsonArray.map { it.jsonObject }
        assertEquals("walk", legs[0]["kind"]!!.jsonPrimitive.content)
        assertEquals("5 мин", legs[0]["duration_text"]!!.jsonPrimitive.content)
        assertEquals("transport", legs[1]["kind"]!!.jsonPrimitive.content)
        assertEquals("Автобус 12", legs[1]["line"]!!.jsonPrimitive.content)
        assertEquals("bus", legs[1]["vehicle"]!!.jsonPrimitive.content)
        assertEquals(7, legs[1]["stops"]!!.jsonPrimitive.int)
        assertEquals("transfer", legs[2]["kind"]!!.jsonPrimitive.content)
        assertEquals("Метро Тверская", legs[2]["to"]!!.jsonPrimitive.content)
        // A transfer with an unknown duration omits the key rather than inventing one.
        assertTrue("duration_text" !in legs[2])
    }

    @Test
    fun `unknown route scalars are explicit json null`() {
        val route = GeoRoute(null, null, null, null, null, emptyList())
        val out = GeoJson.route(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0), TravelMode.WALKING, listOf(route))
        val root = json.parseToJsonElement(out).jsonObject
        val r = root["routes"]!!.jsonArray.single().jsonObject

        assertTrue(r["duration_text"] is JsonNull)
        assertTrue(r["duration_min"] is JsonNull)
        assertTrue(r["transfers"] is JsonNull)
        assertTrue(r["arrival_text"] is JsonNull)
        assertTrue(r["walking_distance_text"] is JsonNull)
        assertTrue(r["legs"]!!.jsonArray.isEmpty())
        assertEquals("walking", root["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `duration_min rounds seconds to whole minutes`() {
        // 95 s = 1.58 min -> 2.
        val route = GeoRoute(null, 95.0, null, null, null, emptyList())
        val r = json.parseToJsonElement(
            GeoJson.route(GeoPoint(0.0, 0.0), GeoPoint(1.0, 1.0), TravelMode.TRANSIT, listOf(route)),
        ).jsonObject["routes"]!!.jsonArray.single().jsonObject

        assertEquals(2, r["duration_min"]!!.jsonPrimitive.int)
    }

    @Test
    fun `an empty places result still has the places key`() {
        val root = json.parseToJsonElement(GeoJson.places(emptyList())).jsonObject
        assertTrue(root["places"]!!.jsonArray.isEmpty())
    }
}
