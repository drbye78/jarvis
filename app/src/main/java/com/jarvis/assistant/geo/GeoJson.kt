package com.jarvis.assistant.geo

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * Domain -> LLM-legible JSON for the geo tools.
 *
 * Deliberately pure and Android-free (the house `buildJsonObject` idiom, same
 * as `OpenMeteoWeatherClient`): the tool lane composes these with its own
 * localized error text, so there are no user-facing strings here. Numbers are
 * emitted as JSON NUMBERS, never quoted strings — the LLM must be able to read
 * `lat`/`lon`/`duration_min`/`transfers` directly.
 *
 * Route-level scalar fields are always present (JSON `null` when unknown) so
 * the model sees a stable shape; optional leg fields are omitted when absent
 * to keep the common transit route compact.
 */
object GeoJson {

    /** `{"places":[{"name":...,"address":...,"lat":..,"lon":..,"uri":...}]}` */
    fun places(places: List<GeoPlace>): String = buildJsonObject {
        put("places", buildJsonArray { places.forEach { add(placeJson(it)) } })
    }.toString()

    /** `{"origin":{...},"destination":{...},"mode":"transit","routes":[...]}` */
    fun route(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode,
        routes: List<GeoRoute>,
    ): String = buildJsonObject {
        put("origin", pointJson(origin))
        put("destination", pointJson(destination))
        put("mode", mode.name.lowercase())
        put("routes", buildJsonArray { routes.forEach { add(routeJson(it)) } })
    }.toString()

    private fun placeJson(place: GeoPlace): JsonObject = buildJsonObject {
        put("name", place.name)
        put("address", place.address.orNull())
        // Numeric, not string: the model must be able to compare/route on these.
        put("lat", place.point.latitude)
        put("lon", place.point.longitude)
        put("uri", place.uri.orNull())
    }

    private fun pointJson(point: GeoPoint): JsonObject = buildJsonObject {
        put("lat", point.latitude)
        put("lon", point.longitude)
    }

    private fun routeJson(route: GeoRoute): JsonObject = buildJsonObject {
        put("duration_text", route.durationText.orNull())
        // Seconds -> whole minutes (the spec's `duration_min`); null when unknown.
        put("duration_min", route.durationSeconds?.div(60.0)?.roundToInt().orNull())
        put("transfers", route.transfers.orNull())
        put("arrival_text", route.arrivalText.orNull())
        put("walking_distance_text", route.walkingDistanceText.orNull())
        put("legs", buildJsonArray { route.legs.forEach { add(legJson(it)) } })
    }

    private fun legJson(leg: GeoLeg): JsonObject = when (leg) {
        is GeoLeg.Walk -> buildJsonObject {
            put("kind", "walk")
            leg.durationText?.let { put("duration_text", it) }
        }

        is GeoLeg.Transport -> buildJsonObject {
            put("kind", "transport")
            put("line", leg.line)
            leg.vehicle?.let { put("vehicle", it) }
            // Present only when MapKit reported usable stop data (never fabricated).
            leg.stops?.let { put("stops", it) }
            leg.durationText?.let { put("duration_text", it) }
        }

        is GeoLeg.Transfer -> buildJsonObject {
            put("kind", "transfer")
            leg.to?.let { put("to", it) }
            leg.durationText?.let { put("duration_text", it) }
        }
    }

    /** Always writes the key: a null becomes an explicit JSON `null`, not an omission. */
    private fun String?.orNull(): JsonPrimitive = this?.let { JsonPrimitive(it) } ?: JsonNull

    private fun Int?.orNull(): JsonPrimitive = this?.let { JsonPrimitive(it) } ?: JsonNull
}
