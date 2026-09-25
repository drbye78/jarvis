package com.jarvis.assistant.geo

/**
 * Geography capability contract.
 *
 * Geo is a CAPABILITY, not a selectable provider — there is exactly one
 * implementation ([com.jarvis.assistant.geo.mapkit.YandexMapKitGeoClient]) and
 * no Settings radio. This mirrors `WeatherClient`/`OpenMeteoWeatherClient`
 * rather than the sealed-provider pattern used for user-selectable backends.
 *
 * This lane takes ALREADY-RESOLVED coordinates: it intentionally does NOT
 * depend on `com.jarvis.assistant.location.*`. Resolving a city name or a GPS
 * fix is the tool lane's job, which keeps this package free of permissions and
 * of the location API.
 *
 * All three functions are cancellable (barge-in): the MapKit-backed
 * implementation cancels the underlying SDK session when the caller's
 * coroutine is cancelled.
 */
interface GeoToolClient {
    /** Forward geocode + organization/place search. Empty list => NOT_FOUND territory. */
    suspend fun searchPlaces(query: String, near: GeoPoint?, limit: Int): GeoResult<List<GeoPlace>>

    /** Best-effort reverse geocode: a human label for a coordinate (used to NAME the origin). */
    suspend fun resolveLabel(point: GeoPoint): GeoResult<String>

    /** Route between two points. [alternatives] is a hint; return what the SDK gives. */
    suspend fun route(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode,
        alternatives: Int,
    ): GeoResult<List<GeoRoute>>
}
