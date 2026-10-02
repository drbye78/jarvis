package com.jarvis.assistant

import com.jarvis.assistant.geo.GeoError
import com.jarvis.assistant.geo.GeoJson
import com.jarvis.assistant.geo.GeoLeg
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
import com.jarvis.assistant.tools.GeoPlaceTool
import com.jarvis.assistant.tools.GeoRouteTool
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The geo tools' argument handling and error mapping, JVM-only (the fake
 * client stands in for the MapKit-backed one; the native SDK is covered by the
 * device smoke, not here).
 *
 * The load-bearing property is that a user never hears a raw failure mode:
 * every [GeoError] resolves to its distinct, actionable message, and
 * `findPlace` keeps working when location is unavailable (a query like «аптека
 * в Москве» does not need an origin) while `getRoute` honestly refuses.
 */
class GeoToolsTest {

    private val messages = DefaultGeoToolMessages

    private fun resolver(
        outcome: LocationOutcome,
        deviceOutcome: LocationOutcome = outcome,
    ): LocationResolver = object : LocationResolver {
        override suspend fun resolve(): LocationOutcome = outcome

        override suspend fun resolveDevice(): LocationOutcome = deviceOutcome
    }

    private fun place(name: String, lat: Double = 55.97, lon: Double = 37.41) =
        GeoPlace(name, "адрес", GeoPoint(lat, lon), null)

    private val coordsFixed = LocationOutcome.Resolved(ResolvedLocation.Coords(55.0, 37.0, "текущее местоположение"))

    private class RouteCall(
        val origin: GeoPoint,
        val destination: GeoPoint,
        val mode: TravelMode,
        val alternatives: Int,
    )

    private class FakeGeoClient(
        var onSearch: (String, GeoPoint?, Int) -> GeoResult<List<GeoPlace>> =
            { _, _, _ -> GeoResult.Ok(emptyList()) },
        var onRoute: (GeoPoint, GeoPoint, TravelMode, Int) -> GeoResult<List<GeoRoute>> =
            { _, _, _, _ -> GeoResult.Ok(emptyList()) },
    ) : GeoToolClient {
        val searchCalls = mutableListOf<Triple<String, GeoPoint?, Int>>()
        val routeCalls = mutableListOf<RouteCall>()

        override suspend fun searchPlaces(query: String, near: GeoPoint?, limit: Int): GeoResult<List<GeoPlace>> {
            searchCalls += Triple(query, near, limit)
            return onSearch(query, near, limit)
        }

        override suspend fun resolveLabel(point: GeoPoint): GeoResult<String> = GeoResult.Ok("label")

        override suspend fun route(
            origin: GeoPoint,
            destination: GeoPoint,
            mode: TravelMode,
            alternatives: Int,
        ): GeoResult<List<GeoRoute>> {
            routeCalls += RouteCall(origin, destination, mode, alternatives)
            return onRoute(origin, destination, mode, alternatives)
        }
    }

    // ---- findPlace ------------------------------------------------------

    @Test
    fun `findPlace rejects a missing or blank query`() = runBlocking {
        val tool = GeoPlaceTool(FakeGeoClient(), resolver(LocationOutcome.PermissionDenied), messages)

        assertTrue(tool.execute("{}").contains("Missing required parameter: query"))
        assertTrue(tool.execute("""{"query":"   "}""").contains("Missing required parameter: query"))
    }

    @Test
    fun `findPlace returns the places JSON on a hit`(): Unit = runBlocking {
        val hits = listOf(place("Аптека"), place("Аптека №2"))
        val tool = GeoPlaceTool(
            FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Ok(hits) }),
            resolver(LocationOutcome.PermissionDenied),
            messages,
        )

        assertEquals(GeoJson.places(hits), tool.execute("""{"query":"аптека"}"""))
    }

    @Test
    fun `findPlace still searches with no near when location is unavailable`(): Unit = runBlocking {
        // The whole point of the lenient policy: a place-named query must not
        // fail just because the default location could not be resolved.
        val client = FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Аптека"))) })
        val tool = GeoPlaceTool(client, resolver(LocationOutcome.Unavailable), messages)

        val out = tool.execute("""{"query":"аптека в Москве"}""")

        assertFalse(out.contains("error"))
        val queryCall = client.searchCalls.last()
        assertEquals("аптека в Москве", queryCall.first)
        assertEquals(null, queryCall.second)
    }

    @Test
    fun `findPlace geocodes an explicit near and passes its point`(): Unit = runBlocking {
        val client = FakeGeoClient(onSearch = { query, _, _ ->
            if (query == "Москва") GeoResult.Ok(listOf(place("Москва", 55.75, 37.61))) else GeoResult.Ok(emptyList())
        })
        val tool = GeoPlaceTool(client, resolver(LocationOutcome.PermissionDenied), messages)

        tool.execute("""{"query":"аптека","near":"Москва"}""")

        // First call resolves `near`; the real search then carries that point.
        assertEquals("Москва", client.searchCalls.first().first)
        assertEquals(GeoPoint(55.75, 37.61), client.searchCalls.last().second)
    }

    @Test
    fun `findPlace near_user biases the search toward the device position`(): Unit = runBlocking {
        val client = FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Аптека"))) })
        // Default resolver returns a configured place, but near_user must force
        // the DEVICE fix instead (coordsFixed = 55.0, 37.0).
        val tool = GeoPlaceTool(
            client,
            resolver(LocationOutcome.Resolved(ResolvedLocation.Place("Сочи")), coordsFixed),
            messages,
        )

        val out = tool.execute("""{"query":"аптека","near_user":true}""")

        assertFalse(out.contains("error"))
        assertEquals(GeoPoint(55.0, 37.0), client.searchCalls.last().second)
    }

    @Test
    fun `findPlace near_user falls back to the configured city when the device fix is denied`(): Unit = runBlocking {
        // The user asked for their OWN position, but there is no permission: the
        // configured city is an honest fallback and keeps «рядом» answerable.
        val client = FakeGeoClient(onSearch = { query, _, _ ->
            if (query == "Сочи") {
                GeoResult.Ok(listOf(place("Сочи", 55.75, 37.61)))
            } else {
                GeoResult.Ok(listOf(place("Аптека")))
            }
        })
        val tool = GeoPlaceTool(
            client,
            resolver(
                LocationOutcome.Resolved(ResolvedLocation.Place("Сочи")),
                deviceOutcome = LocationOutcome.PermissionDenied,
            ),
            messages,
        )

        val out = tool.execute("""{"query":"аптека","near_user":true}""")

        assertFalse(out.contains("error"))
        // The device fix failed, so the configured city's point biases the search.
        assertEquals("Сочи", client.searchCalls.first().first)
        assertEquals(GeoPoint(55.75, 37.61), client.searchCalls.last().second)
    }

    @Test
    fun `findPlace near_user falls back to the configured city when the device reports no fix`(): Unit = runBlocking {
        val client = FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Аптека"))) })
        val tool = GeoPlaceTool(
            client,
            resolver(
                LocationOutcome.Resolved(ResolvedLocation.Coords(59.94, 30.31, "Санкт-Петербург")),
                deviceOutcome = LocationOutcome.Unavailable,
            ),
            messages,
        )

        val out = tool.execute("""{"query":"аптека","near_user":true}""")

        assertFalse(out.contains("error"))
        assertEquals(GeoPoint(59.94, 30.31), client.searchCalls.last().second)
    }

    // ---- getRoute -------------------------------------------------------

    @Test
    fun `getRoute rejects a missing destination`() = runBlocking {
        val tool = GeoRouteTool(FakeGeoClient(), resolver(LocationOutcome.Unavailable), messages)

        assertTrue(tool.execute("{}").contains("Missing required parameter: destination"))
    }

    @Test
    fun `getRoute refuses to build a route without an origin`(): Unit = runBlocking {
        val client = FakeGeoClient()
        val tool = GeoRouteTool(client, resolver(LocationOutcome.PermissionDenied), messages)

        val out = tool.execute("""{"destination":"Шереметьево"}""")

        assertEquals(JsonOut.error(messages.locationDenied), out)
        assertTrue("no route may be attempted without an origin", client.routeCalls.isEmpty())
    }

    @Test
    fun `getRoute maps an unavailable origin to its honest message`(): Unit = runBlocking {
        val tool = GeoRouteTool(FakeGeoClient(), resolver(LocationOutcome.Unavailable), messages)

        assertEquals(
            JsonOut.error(messages.locationUnavailable),
            tool.execute("""{"destination":"Шереметьево"}"""),
        )
    }

    @Test
    fun `getRoute defaults an unknown mode to transit`(): Unit = runBlocking {
        val client = FakeGeoClient(
            onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Шереметьево"))) },
            onRoute = { _, _, _, _ -> GeoResult.Ok(sampleRoutes()) },
        )
        val tool = GeoRouteTool(client, resolver(coordsFixed), messages)

        tool.execute("""{"destination":"Шереметьево","mode":"teleport"}""")

        assertEquals(TravelMode.TRANSIT, client.routeCalls.single().mode)
    }

    @Test
    fun `getRoute origin_user starts from the device position ignoring the configured city`(): Unit = runBlocking {
        val client = FakeGeoClient(
            onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Шереметьево"))) },
            onRoute = { _, _, _, _ -> GeoResult.Ok(sampleRoutes()) },
        )
        val tool = GeoRouteTool(
            client,
            resolver(LocationOutcome.Resolved(ResolvedLocation.Place("Сочи")), coordsFixed),
            messages,
        )

        tool.execute("""{"destination":"Шереметьево","origin_user":true}""")

        assertEquals(GeoPoint(55.0, 37.0), client.routeCalls.single().origin)
    }

    @Test
    fun `getRoute origin_user falls back to the configured location when the device fix is unavailable`(): Unit = runBlocking {
        val client = FakeGeoClient(
            onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Шереметьево"))) },
            onRoute = { _, _, _, _ -> GeoResult.Ok(sampleRoutes()) },
        )
        val tool = GeoRouteTool(
            client,
            resolver(
                LocationOutcome.Resolved(ResolvedLocation.Coords(59.94, 30.31, "текущее местоположение")),
                deviceOutcome = LocationOutcome.Unavailable,
            ),
            messages,
        )

        tool.execute("""{"destination":"Шереметьево","origin_user":true}""")

        // The device fix failed, so the route starts from the configured location.
        assertEquals(GeoPoint(59.94, 30.31), client.routeCalls.single().origin)
    }

    @Test
    fun `getRoute explicit origin wins over origin_user`(): Unit = runBlocking {
        val client = FakeGeoClient(
            onSearch = { query, _, _ ->
                if (query == "Тверь") {
                    GeoResult.Ok(listOf(place("Тверь", 56.86, 35.91)))
                } else {
                    GeoResult.Ok(listOf(place("Шереметьево")))
                }
            },
            onRoute = { _, _, _, _ -> GeoResult.Ok(sampleRoutes()) },
        )
        val tool = GeoRouteTool(
            client,
            resolver(
                LocationOutcome.Resolved(ResolvedLocation.Coords(59.94, 30.31, "label")),
                deviceOutcome = coordsFixed,
            ),
            messages,
        )

        tool.execute("""{"destination":"Шереметьево","origin":"Тверь","origin_user":true}""")

        assertEquals(GeoPoint(56.86, 35.91), client.routeCalls.single().origin)
    }

    @Test
    fun `getRoute honours an explicit walking mode`(): Unit = runBlocking {
        val client = FakeGeoClient(
            onSearch = { _, _, _ -> GeoResult.Ok(listOf(place("Парк"))) },
            onRoute = { _, _, _, _ -> GeoResult.Ok(sampleRoutes()) },
        )
        val tool = GeoRouteTool(client, resolver(coordsFixed), messages)

        tool.execute("""{"destination":"Парк","mode":"walking"}""")

        assertEquals(TravelMode.WALKING, client.routeCalls.single().mode)
    }

    @Test
    fun `getRoute returns the routes JSON and asks for alternatives`(): Unit = runBlocking {
        val origin = GeoPoint(55.0, 37.0)
        val destination = GeoPoint(55.97, 37.41)
        val routes = sampleRoutes()
        val client = FakeGeoClient(
            onSearch = { _, _, _ ->
                GeoResult.Ok(listOf(place("Шереметьево", destination.latitude, destination.longitude)))
            },
            onRoute = { _, _, _, _ -> GeoResult.Ok(routes) },
        )
        val tool = GeoRouteTool(client, resolver(coordsFixed), messages)

        val out = tool.execute("""{"destination":"Шереметьево"}""")

        assertEquals(GeoJson.route(origin, destination, TravelMode.TRANSIT, routes), out)
        assertTrue(
            "alternatives must be 2..3 so a follow-up can be answered",
            client.routeCalls.single().alternatives in 2..3,
        )
    }

    @Test
    fun `getRoute reports routeNotFound when the destination cannot be geocoded`(): Unit = runBlocking {
        val client = FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Ok(emptyList()) })
        val tool = GeoRouteTool(client, resolver(coordsFixed), messages)

        assertEquals(JsonOut.error(messages.routeNotFound), tool.execute("""{"destination":"Нигде"}"""))
        assertTrue(client.routeCalls.isEmpty())
    }

    @Test
    fun `getRoute maps a client NOT_FOUND destination to routeNotFound`(): Unit = runBlocking {
        val client = FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Err(GeoError.NOT_FOUND) })
        val tool = GeoRouteTool(client, resolver(coordsFixed), messages)

        assertEquals(JsonOut.error(messages.routeNotFound), tool.execute("""{"destination":"Нигде"}"""))
    }

    // ---- GeoError -> message mapping ------------------------------------

    @Test
    fun `every findPlace GeoError maps to its distinct message`(): Unit = runBlocking {
        val expected = mapOf(
            GeoError.NO_KEY to messages.noKey,
            GeoError.KEY_CHANGED to messages.keyChanged,
            GeoError.PERMISSION_DENIED to messages.locationDenied,
            GeoError.UNAVAILABLE to messages.locationUnavailable,
            GeoError.NOT_FOUND to messages.placeNotFound,
            GeoError.FAILED to messages.serviceFailed,
        )
        expected.forEach { (error, message) ->
            val tool = GeoPlaceTool(
                FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Err(error) }),
                resolver(LocationOutcome.PermissionDenied),
                messages,
            )
            assertEquals("wrong message for $error", JsonOut.error(message), tool.execute("""{"query":"x"}"""))
        }
    }

    @Test
    fun `every non-NOT_FOUND getRoute GeoError maps to its distinct message`(): Unit = runBlocking {
        // NOT_FOUND is excluded here because the route context maps it to
        // routeNotFound (covered above); the rest must be identical to findPlace.
        val expected = mapOf(
            GeoError.NO_KEY to messages.noKey,
            GeoError.KEY_CHANGED to messages.keyChanged,
            GeoError.PERMISSION_DENIED to messages.locationDenied,
            GeoError.UNAVAILABLE to messages.locationUnavailable,
            GeoError.FAILED to messages.serviceFailed,
        )
        expected.forEach { (error, message) ->
            val tool = GeoRouteTool(
                FakeGeoClient(onSearch = { _, _, _ -> GeoResult.Err(error) }),
                resolver(coordsFixed),
                messages,
            )
            assertEquals("wrong message for $error", JsonOut.error(message), tool.execute("""{"destination":"x"}"""))
        }
    }

    @Test
    fun `findPlace maps an empty result to placeNotFound`(): Unit = runBlocking {
        val tool = GeoPlaceTool(FakeGeoClient(), resolver(LocationOutcome.PermissionDenied), messages)

        assertEquals(JsonOut.error(messages.placeNotFound), tool.execute("""{"query":"несуществующее"}"""))
    }

    private fun sampleRoutes() = listOf(
        GeoRoute(
            durationText = "42 мин",
            durationSeconds = 2520.0,
            transfers = 1,
            arrivalText = "14:35",
            walkingDistanceText = "800 м",
            legs = listOf(
                GeoLeg.Walk("5 мин"),
                GeoLeg.Transport("Автобус 12", "bus", 7, "20 мин"),
            ),
        ),
    )
}
