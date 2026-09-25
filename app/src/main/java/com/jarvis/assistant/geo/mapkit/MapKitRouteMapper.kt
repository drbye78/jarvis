package com.jarvis.assistant.geo.mapkit

import com.jarvis.assistant.geo.GeoLeg
import com.jarvis.assistant.geo.GeoRoute
import com.yandex.mapkit.transport.masstransit.Route
import com.yandex.mapkit.transport.masstransit.Section
import com.yandex.mapkit.transport.masstransit.Transport

/**
 * MapKit masstransit `Route` -> [GeoRoute] mapper.
 *
 * WHY THE PROJECTION: MapKit 4.45.0 ships Java 21 bytecode (class file version
 * 65) while this project's JVM test runtime is Java 17 (61), so NO MapKit class
 * — not even a pure-value one — can be loaded in a unit test. The traversal
 * logic therefore runs on the MapKit-free [RouteView] projection below, which
 * the JVM suite exercises through [mapRouteViews]; this object is only the
 * thin, MapKit-aware adapter that feeds it (`map` is the frozen public entry).
 *
 * Traversal mirrors the verified contract: route weight/estimation, then
 * sections where an EMPTY `SectionData.transports` means a WALKING leg and a
 * non-null `SectionData.transfer` means a transfer. A missing field degrades
 * to null — never throws.
 */
object MapKitRouteMapper {

    /** Production entry point: MapKit routes -> domain routes. */
    fun map(routes: List<Route>): List<GeoRoute> = mapRouteViews(routes.map { it.toView() })

    // ------------------------------------------------------------------
    // MapKit projection — the only MapKit-touching code in this file.
    // ------------------------------------------------------------------

    private fun Route.toView(): RouteView {
        val metadata = metadata
        val weight = metadata?.weight
        return RouteView(
            durationText = weight?.time?.text,
            durationSeconds = weight?.time?.value,
            // NOTE: transfersCount lives on Weight, not Summary.
            transfers = weight?.transfersCount,
            walkingDistanceText = weight?.walkingDistance?.text,
            arrivalText = metadata?.estimation?.arrivalTime?.text,
            sections = sections?.map { it.toView() }.orEmpty(),
        )
    }

    private fun Section.toView(): SectionView {
        val metadata = metadata
        val data = metadata?.data
        return SectionView(
            transports = data?.transports?.map { it.toView() }.orEmpty(),
            // Transfer -> TransferStop -> RouteStop -> RouteStopMetadata -> Stop.
            transferTo = data?.transfer?.transferStop?.routeStop?.metadata?.stop?.name,
            // `getStops()` is the boarding->alighting list; size 0 is meaningless.
            stopCount = stops?.size?.takeIf { it > 0 },
            durationText = metadata?.weight?.time?.text,
            arrivalText = metadata?.estimation?.arrivalTime?.text,
        )
    }

    private fun Transport.toView(): TransportView {
        val line = line
        return TransportView(
            // shortName is the user-facing one («Метро»), but it is often blank.
            line = line?.shortName?.takeIf { it.isNotBlank() } ?: line?.name,
            vehicle = line?.vehicleTypes?.firstOrNull(),
        )
    }
}

/**
 * The pure mapping core, kept top-level so unit tests never load a class whose
 * method descriptors reference MapKit types (see the object KDoc).
 */
internal fun mapRouteViews(views: List<RouteView>): List<GeoRoute> = views.map { view ->
    GeoRoute(
        durationText = view.durationText,
        durationSeconds = view.durationSeconds,
        transfers = view.transfers,
        // Route-level arrival is preferred; fall back to the first section that
        // knew one (a route estimation is not always populated).
        arrivalText = view.arrivalText ?: view.sections.firstNotNullOfOrNull { it.arrivalText },
        walkingDistanceText = view.walkingDistanceText,
        legs = view.sections.flatMap { it.toLegs() },
    )
}

private fun SectionView.toLegs(): List<GeoLeg> = when {
    // Non-empty transports win over a transfer: MapKit models a section as
    // exactly one variant, but being explicit keeps the precedence stable if
    // the SDK ever surfaces both.
    transports.isNotEmpty() -> transports.map { transport ->
        GeoLeg.Transport(
            line = transport.line.orEmpty(),
            vehicle = transport.vehicle,
            stops = stopCount,
            durationText = durationText,
        )
    }

    transferTo != null -> listOf(GeoLeg.Transfer(to = transferTo, durationText = durationText))

    // No transports and no transfer: MapKit's only remaining variant is a
    // walking leg. It must be kept, not dropped.
    else -> listOf(GeoLeg.Walk(durationText = durationText))
}

/** MapKit-free projection of one route (see the object KDoc for WHY). */
internal data class RouteView(
    val durationText: String?,
    val durationSeconds: Double?,
    val transfers: Int?,
    val walkingDistanceText: String?,
    val arrivalText: String?,
    val sections: List<SectionView>,
)

/** MapKit-free projection of one section. */
internal data class SectionView(
    /** Non-empty => a transport leg; empty => walking or transfer. */
    val transports: List<TransportView>,
    /** Transfer stop name when this section is a transfer. */
    val transferTo: String?,
    val stopCount: Int?,
    val durationText: String?,
    val arrivalText: String?,
)

/** MapKit-free projection of the line/vehicle behind a transport leg. */
internal data class TransportView(
    val line: String?,
    val vehicle: String?,
)
