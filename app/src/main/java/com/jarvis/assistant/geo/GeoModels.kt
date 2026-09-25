package com.jarvis.assistant.geo

/** WGS84 coordinate. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** A place found by search/geocode. [uri] is the Yandex Maps URI when present. */
data class GeoPlace(val name: String, val address: String?, val point: GeoPoint, val uri: String?)

/** How to travel. Both are backed by verified MapKit routers. */
enum class TravelMode { TRANSIT, WALKING }

/** One leg of a route. A walking leg is identified by MapKit returning NO transports. */
sealed interface GeoLeg {
    val durationText: String?

    data class Walk(override val durationText: String?) : GeoLeg

    data class Transport(
        /** e.g. «Автобус 12» / «Метро». */
        val line: String,
        /** Raw `Line.getVehicleTypes()` first entry, e.g. "bus"/"underground". */
        val vehicle: String?,
        /** Boarding -> alighting stop count, when derivable. */
        val stops: Int?,
        override val durationText: String?,
    ) : GeoLeg

    data class Transfer(val to: String?, override val durationText: String?) : GeoLeg
}

/** One route alternative. */
data class GeoRoute(
    val durationText: String?,
    val durationSeconds: Double?,
    val transfers: Int?,
    val arrivalText: String?,
    val walkingDistanceText: String?,
    val legs: List<GeoLeg>,
)

/** Typed failure — never an exception. NEVER leaks a MapKit `Error` type. */
enum class GeoError { NO_KEY, KEY_CHANGED, PERMISSION_DENIED, UNAVAILABLE, NOT_FOUND, FAILED }

/** Result envelope. */
sealed interface GeoResult<out T> {
    data class Ok<T>(val value: T) : GeoResult<T>

    data class Err(val error: GeoError, val detail: String? = null) : GeoResult<Nothing>
}
