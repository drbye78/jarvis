package com.jarvis.assistant.geo.mapkit

import com.jarvis.assistant.geo.GeoError
import com.jarvis.assistant.geo.GeoPlace
import com.jarvis.assistant.geo.GeoPoint
import com.yandex.mapkit.GeoObject
import com.yandex.mapkit.geometry.BoundingBox
import com.yandex.mapkit.geometry.Geometry
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.search.BusinessObjectMetadata
import com.yandex.mapkit.search.Response
import com.yandex.mapkit.search.SearchFactory
import com.yandex.mapkit.search.SearchManagerType
import com.yandex.mapkit.search.SearchOptions
import com.yandex.mapkit.search.SearchType
import com.yandex.mapkit.search.Session
import com.yandex.mapkit.search.ToponymObjectMetadata
import com.yandex.mapkit.uri.UriObjectMetadata
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import com.yandex.runtime.Error as MapKitError

/**
 * Typed bridge for a MapKit callback/native failure. Keeps
 * `com.yandex.runtime.Error` from escaping this package: callers only ever see
 * a [GeoError] plus an optional detail string.
 */
internal class MapKitFailure(
    val error: GeoError,
    val detail: String? = null,
) : Exception(detail)

/**
 * Thin suspend wrapper over MapKit's online search singleton.
 *
 * All native work runs on [Dispatchers.Main.immediate]: MapKit callbacks are
 * delivered on the UI thread, and the native manager is not thread-safe across
 * arbitrary dispatchers. The caller ([YandexMapKitGeoClient]) has already
 * ensured MapKit is initialized.
 */
class MapKitSearch {

    /** Forward geocode + organization/place search. An empty result is legitimate. */
    suspend fun forward(query: String, near: GeoPoint?, limit: Int): List<GeoPlace> =
        withContext(Dispatchers.Main.immediate) {
            val manager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
            val options = SearchOptions()
                // GEO (addresses/toponyms) + BIZ (organizations): both as a bitmask.
                .setSearchTypes(SearchType.GEO.value or SearchType.BIZ.value)
                .setResultPageSize(limit)
            near?.let { options.setUserPosition(Point(it.latitude, it.longitude)) }
            // The area is deliberately the whole world; `near` disambiguates by
            // RANKING (setUserPosition), not by clipping the search to a point.
            awaitSearch { listener -> manager.submit(query, WORLD_AREA, options, listener) }.toPlaces()
        }

    /** Reverse geocode: a human label for a coordinate, or null when MapKit has none. */
    suspend fun reverse(point: GeoPoint): String? =
        withContext(Dispatchers.Main.immediate) {
            val manager = SearchFactory.getInstance().createSearchManager(SearchManagerType.ONLINE)
            val options = SearchOptions()
                .setSearchTypes(SearchType.GEO.value)
                .setResultPageSize(1)
            awaitSearch { listener ->
                manager.submit(Point(point.latitude, point.longitude), REVERSE_ZOOM, options, listener)
            }.toPlaces().firstOrNull()?.name?.takeIf { it.isNotBlank() }
        }

    private fun Response.toPlaces(): List<GeoPlace> =
        collection?.children.orEmpty().mapNotNull { item ->
            val obj = item.obj ?: return@mapNotNull null
            // A place without a point is unusable as a route endpoint.
            val point = obj.geometry?.firstOrNull()?.point ?: return@mapNotNull null
            GeoPlace(
                name = obj.name.orEmpty(),
                address = obj.address(),
                point = GeoPoint(point.latitude, point.longitude),
                uri = obj.uri(),
            )
        }

    /** BIZ first (richer address), then toponym, then the free-text description. */
    private fun GeoObject.address(): String? =
        metadataContainer?.getItem(BusinessObjectMetadata::class.java)?.address?.formattedAddress
            ?: metadataContainer?.getItem(ToponymObjectMetadata::class.java)?.address?.formattedAddress
            ?: descriptionText?.takeIf { it.isNotBlank() }

    private fun GeoObject.uri(): String? =
        metadataContainer?.getItem(UriObjectMetadata::class.java)?.uris?.firstOrNull()?.value

    private companion object {
        /** Reverse-geocode zoom: city-block detail, not rooftop. */
        const val REVERSE_ZOOM = 16

        /**
         * Unconstrained search area. `SearchManager.submit` requires a
         * NON-NULL Geometry, so a null `near` must still send one; the world
         * bounding box is the honest "no area limit" value.
         */
        val WORLD_AREA: Geometry = Geometry.fromBoundingBox(
            BoundingBox(Point(-90.0, -180.0), Point(90.0, 180.0)),
        )
    }
}

@Suppress("TooGenericExceptionCaught")
private suspend fun awaitSearch(
    submit: (Session.SearchListener) -> Session,
): Response = suspendCancellableCoroutine { continuation ->
    val resumed = AtomicBoolean(false)
    val listener = object : Session.SearchListener {
        override fun onSearchResponse(response: Response) {
            continuation.resumeOnce(resumed, response)
        }

        override fun onSearchError(error: MapKitError) {
            continuation.resumeOnceWithException(resumed, MapKitFailure(GeoError.FAILED, error.toString()))
        }
    }
    val session = try {
        submit(listener)
    } catch (t: Throwable) {
        // Native MapKit submit can surface arbitrary RuntimeException/Error
        // shapes (including UnsatisfiedLinkError); this boundary must not let
        // any of them escape into the caller's coroutine.
        continuation.resumeOnceWithException(resumed, MapKitFailure(GeoError.FAILED, t.message))
        return@suspendCancellableCoroutine
    }
    // Barge-in: cancelling the caller's coroutine cancels the SDK session. A
    // session that already completed may reject cancel(), which is harmless.
    continuation.invokeOnCancellation { runCatching { session.cancel() } }
}

/**
 * Atomic single-resume guard. A late MapKit callback (arriving after
 * cancellation or after the other callback) is dropped instead of resuming a
 * continuation twice. Resuming an already-CANCELLED continuation is a
 * documented no-op in kotlinx.coroutines, so no `isActive`/`tryResume` guard is
 * needed (and `tryResume` is an internal API this module must not use).
 */
internal fun <T> CancellableContinuation<T>.resumeOnce(resumed: AtomicBoolean, value: T) {
    if (resumed.compareAndSet(false, true)) resume(value)
}

/** [resumeOnce] for the failure path. */
internal fun <T> CancellableContinuation<T>.resumeOnceWithException(resumed: AtomicBoolean, cause: Throwable) {
    if (resumed.compareAndSet(false, true)) resumeWithException(cause)
}
