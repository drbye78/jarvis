package com.jarvis.assistant

import com.jarvis.assistant.geo.GeoLeg
import com.jarvis.assistant.geo.mapkit.RouteView
import com.jarvis.assistant.geo.mapkit.SectionView
import com.jarvis.assistant.geo.mapkit.TransportView
import com.jarvis.assistant.geo.mapkit.mapRouteViews
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the route traversal that drives every spoken transit answer.
 *
 * MapKit 4.45.0 is Java 21 bytecode while the JVM test runtime is Java 17, so
 * no MapKit type can be constructed here; the fixtures are [RouteView]
 * projections instead. That still exercises every decision the mapper makes
 * (walk-vs-transport-vs-transfer precedence, line/vehicle selection, stop
 * count, arrival fallback, null degradation) — the only untested part is the
 * getter calls in `MapKitRouteMapper.map`, which need a device.
 */
class MapKitRouteMapperTest {

    private fun route(
        durationText: String? = "42 мин",
        durationSeconds: Double? = 2520.0,
        transfers: Int? = 3,
        arrivalText: String? = "14:35",
        walkingDistanceText: String? = "800 м",
        sections: List<SectionView> = emptyList(),
    ) = RouteView(durationText, durationSeconds, transfers, walkingDistanceText, arrivalText, sections)

    private fun section(
        transports: List<TransportView> = emptyList(),
        transferTo: String? = null,
        stopCount: Int? = null,
        durationText: String? = null,
        arrivalText: String? = null,
    ) = SectionView(transports, transferTo, stopCount, durationText, arrivalText)

    @Test
    fun `a transport leg yields line name vehicle and stops`() {
        val result = mapRouteViews(
            listOf(
                route(
                    sections = listOf(
                        section(transports = listOf(TransportView("Автобус 12", "bus")), stopCount = 7),
                    ),
                ),
            ),
        )

        val leg = result.single().legs.single()
        assertTrue(leg is GeoLeg.Transport)
        leg as GeoLeg.Transport
        assertEquals("Автобус 12", leg.line)
        assertEquals("bus", leg.vehicle)
        assertEquals(7, leg.stops)
    }

    @Test
    fun `a transport leg carries the section duration text`() {
        val result = mapRouteViews(
            listOf(
                route(
                    sections = listOf(
                        section(
                            transports = listOf(TransportView("Метро", "underground")),
                            durationText = "20 мин",
                        ),
                    ),
                ),
            ),
        )

        assertEquals("20 мин", (result.single().legs.single() as GeoLeg.Transport).durationText)
    }

    @Test
    fun `a section without transports is a walk and is not dropped`() {
        val result = mapRouteViews(listOf(route(sections = listOf(section(durationText = "5 мин")))))

        val leg = result.single().legs.single()
        assertTrue("a leg with no transports must be Walk, not dropped", leg is GeoLeg.Walk)
        assertEquals("5 мин", (leg as GeoLeg.Walk).durationText)
    }

    @Test
    fun `a transfer section maps to a transfer leg with its stop name`() {
        val result = mapRouteViews(
            listOf(route(sections = listOf(section(transferTo = "Метро Тверская")))),
        )

        val leg = result.single().legs.single()
        assertTrue(leg is GeoLeg.Transfer)
        assertEquals("Метро Тверская", (leg as GeoLeg.Transfer).to)
    }

    @Test
    fun `transfers comes from the route weight`() {
        assertEquals(3, mapRouteViews(listOf(route(transfers = 3))).single().transfers)
        // 0 is meaningful ("no transfers"), not an absent value.
        assertEquals(0, mapRouteViews(listOf(route(transfers = 0))).single().transfers)
    }

    @Test
    fun `route-level arrival is preferred over a section arrival`() {
        val withRoute = route(
            arrivalText = "14:35",
            sections = listOf(section(arrivalText = "14:40")),
        )
        assertEquals("14:35", mapRouteViews(listOf(withRoute)).single().arrivalText)
    }

    @Test
    fun `arrival falls back to the first section that knows one`() {
        val view = route(
            arrivalText = null,
            sections = listOf(
                section(arrivalText = null),
                section(arrivalText = "15:00"),
                section(arrivalText = "16:00"),
            ),
        )
        assertEquals("15:00", mapRouteViews(listOf(view)).single().arrivalText)
    }

    @Test
    fun `missing fields degrade to null without throwing`() {
        val result = mapRouteViews(listOf(RouteView(null, null, null, null, null, emptyList()))).single()

        assertNull(result.durationText)
        assertNull(result.durationSeconds)
        assertNull(result.transfers)
        assertNull(result.arrivalText)
        assertNull(result.walkingDistanceText)
        assertTrue(result.legs.isEmpty())
    }

    @Test
    fun `a missing line name degrades to empty string rather than crashing`() {
        val result = mapRouteViews(
            listOf(route(sections = listOf(section(transports = listOf(TransportView(null, null)))))),
        )

        val leg = result.single().legs.single() as GeoLeg.Transport
        assertEquals("", leg.line)
        assertNull(leg.vehicle)
    }

    @Test
    fun `sections flatten into one leg list in order`() {
        val result = mapRouteViews(
            listOf(
                route(
                    sections = listOf(
                        section(durationText = "5 мин"),
                        section(transports = listOf(TransportView("Автобус 12", "bus")), stopCount = 4),
                        section(transferTo = "Метро Тверская"),
                    ),
                ),
            ),
        )

        val legs = result.single().legs
        assertEquals(3, legs.size)
        assertTrue(legs[0] is GeoLeg.Walk)
        assertTrue(legs[1] is GeoLeg.Transport)
        assertTrue(legs[2] is GeoLeg.Transfer)
    }

    @Test
    fun `route-level scalars pass through unchanged`() {
        val result = mapRouteViews(
            listOf(route(durationText = "42 мин", durationSeconds = 2520.0, walkingDistanceText = "800 м")),
        ).single()

        assertEquals("42 мин", result.durationText)
        assertEquals(2520.0, result.durationSeconds!!, 1e-9)
        assertEquals("800 м", result.walkingDistanceText)
    }
}
