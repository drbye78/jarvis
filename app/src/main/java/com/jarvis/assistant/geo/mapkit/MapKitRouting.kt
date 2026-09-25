package com.jarvis.assistant.geo.mapkit

import com.jarvis.assistant.geo.GeoError
import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.TravelMode
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.transport.TransportFactory
import com.yandex.mapkit.transport.masstransit.Route
import com.yandex.mapkit.transport.masstransit.RouteOptions
import com.yandex.mapkit.transport.masstransit.Session
import com.yandex.mapkit.transport.masstransit.TimeOptions
import com.yandex.mapkit.transport.masstransit.TransitOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import com.yandex.runtime.Error as MapKitError

/**
 * Thin suspend wrapper over MapKit's masstransit and pedestrian routers.
 *
 * Walking is a DISTINCT router (the contract corrects the assumption that it
 * was a masstransit flag), so [TravelMode.WALKING] uses `createPedestrianRouter`
 * and [TravelMode.TRANSIT] uses `createMasstransitRouter`. Native work runs on
 * [Dispatchers.Main.immediate]; the caller has already ensured MapKit is
 * initialized.
 */
class MapKitRouting {

    suspend fun routes(origin: GeoPoint, destination: GeoPoint, mode: TravelMode): List<Route> =
        withContext(Dispatchers.Main.immediate) {
            val points = listOf(origin.toRequestPoint(), destination.toRequestPoint())
            when (mode) {
                TravelMode.TRANSIT -> {
                    val router = TransportFactory.getInstance().createMasstransitRouter()
                    // avoid = 0: the FilterVehicleTypes enum -> bitmask mapping is
                    // UNVERIFIED, so NO vehicle type is filtered out.
                    awaitRoutes { listener ->
                        router.requestRoutes(points, TransitOptions(0, TimeOptions()), RouteOptions(), listener)
                    }
                }

                TravelMode.WALKING -> {
                    val router = TransportFactory.getInstance().createPedestrianRouter()
                    awaitRoutes { listener ->
                        router.requestRoutes(points, TimeOptions(), RouteOptions(), listener)
                    }
                }
            }
        }

    private fun GeoPoint.toRequestPoint(): RequestPoint =
        RequestPoint(Point(latitude, longitude), RequestPointType.WAYPOINT, null, null, null)
}

@Suppress("TooGenericExceptionCaught")
private suspend fun awaitRoutes(
    submit: (Session.RouteListener) -> Session,
): List<Route> = suspendCancellableCoroutine { continuation ->
    val resumed = AtomicBoolean(false)
    val listener = object : Session.RouteListener {
        override fun onMasstransitRoutes(routes: List<Route>) {
            continuation.resumeOnce(resumed, routes)
        }

        override fun onMasstransitRoutesError(error: MapKitError) {
            continuation.resumeOnceWithException(resumed, MapKitFailure(GeoError.FAILED, error.toString()))
        }
    }
    val session = try {
        submit(listener)
    } catch (t: Throwable) {
        // Native routing submit can surface arbitrary RuntimeException/Error
        // shapes; this boundary must not let any of them escape.
        continuation.resumeOnceWithException(resumed, MapKitFailure(GeoError.FAILED, t.message))
        return@suspendCancellableCoroutine
    }
    // Barge-in: cancelling the caller's coroutine cancels the SDK session.
    continuation.invokeOnCancellation { runCatching { session.cancel() } }
}
