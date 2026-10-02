package com.jarvis.assistant.tools

import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.GeoResult
import com.jarvis.assistant.geo.GeoToolClient
import com.jarvis.assistant.location.LocationOutcome
import com.jarvis.assistant.location.LocationResolver
import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * `getCurrentLocation`: the user's ACTUAL device position. There was no tool
 * that could answer «где я нахожусь?», so the model hallucinated a refusal.
 *
 * This is the ONE consumer of [LocationResolver.resolveDevice]: it deliberately
 * ignores the configured city, because the user explicitly asked for where they
 * are NOW. The position is NAMED via [GeoToolClient.resolveLabel] (MapKit
 * reverse geocoding) when possible; when that fails (no key, changed key, not
 * found, transport error) the honest unnamed label is returned alongside the
 * raw coordinates. A city is NEVER invented.
 */
class GetCurrentLocationTool(
    private val resolver: LocationResolver,
    private val geoClient: GeoToolClient,
    private val messages: GeoToolMessages,
    /** Localized label for a position MapKit could not name. */
    private val unnamedLabel: () -> String,
    /** Bounded device-fix + reverse-geocode budget (registry default 15 s). */
    private val budgetMs: Long = 15_000,
) : ToolContract {
    override val name = "getCurrentLocation"
    override val risk = ToolRisk.READ_ONLY
    override val description: String =
        "Get the user's CURRENT device position (latitude, longitude and a best-effort " +
            "human-readable label). Use it for «где я», «где я нахожусь», «моё " +
            "местоположение». It ignores the city configured in Settings and uses the " +
            "device's real position."
    override val parametersJson = schema(emptyMap())

    override val timeoutMs: Long? = budgetMs

    override suspend fun execute(arguments: String): String {
        return when (val outcome = resolver.resolveDevice()) {
            is LocationOutcome.Resolved -> {
                // resolveDevice contractually yields Coords; a Place here would be
                // an internal inconsistency, so degrade honestly rather than guess.
                val coords = outcome.location as? ResolvedLocation.Coords
                    ?: return JsonOut.error(messages.locationUnavailable)
                JsonOut.obj(
                    "status" to "ok",
                    "label" to labelFor(coords),
                    "lat" to coords.latitude,
                    "lon" to coords.longitude,
                )
            }

            LocationOutcome.PermissionDenied -> JsonOut.error(messages.locationDenied)
            LocationOutcome.Unavailable -> JsonOut.error(messages.locationUnavailable)
        }
    }

    /**
     * Best-effort reverse geocode. Any failure (NO_KEY / KEY_CHANGED /
     * NOT_FOUND / FAILED) falls back to the honest unnamed label; it never
     * invents a city. Cancellation is always rethrown (barge-in).
     */
    private suspend fun labelFor(coords: ResolvedLocation.Coords): String = try {
        when (val named = geoClient.resolveLabel(GeoPoint(coords.latitude, coords.longitude))) {
            is GeoResult.Ok -> named.value
            is GeoResult.Err -> unnamedLabel()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // The MapKit client already converts SDK failures into GeoResult, so an
        // exception here is unexpected: log the TYPE only and use the label.
        Timber.w("getCurrentLocation reverse geocode failed: %s", e::class.java.simpleName)
        unnamedLabel()
    }
}
