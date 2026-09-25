package com.jarvis.assistant.tools

import com.jarvis.assistant.geo.GeoError
import com.jarvis.assistant.geo.GeoJson
import com.jarvis.assistant.geo.GeoPlace
import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.GeoResult
import com.jarvis.assistant.geo.GeoToolClient
import com.jarvis.assistant.geo.TravelMode
import com.jarvis.assistant.location.LocationOutcome
import com.jarvis.assistant.location.LocationResolver
import com.jarvis.assistant.location.ResolvedLocation
import com.jarvis.assistant.util.JsonOut
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Spoken/LLM-facing strings for the geography tools. Mirrors
 * [WeatherToolMessages]: the tool lane stays Android-free, production passes
 * the resource-backed `AndroidToolStrings` (which implements this interface),
 * and JVM tests use [DefaultGeoToolMessages].
 *
 * Every member must be honest and actionable: "no key" is a configuration
 * problem the USER can fix, and "key changed" genuinely requires a restart
 * because MapKit cannot be re-keyed inside a live process.
 */
interface GeoToolMessages {
    /** No MapKit API key configured — the feature is unusable until the user adds one. */
    val noKey: String

    /** The key changed after MapKit was initialized — only a process restart can apply it. */
    val keyChanged: String

    /** Location permission refused and no configured city. */
    val locationDenied: String

    /** Permission granted but no usable fix, and no configured city. */
    val locationUnavailable: String

    /** A place search found nothing. */
    val placeNotFound: String

    /** A route could not be built (unknown endpoint, or no route returned). */
    val routeNotFound: String

    /** The map service failed (transport/native error). */
    val serviceFailed: String
}

/** Russian fallback (the product language) — also the JVM-test default. */
object DefaultGeoToolMessages : GeoToolMessages {
    override val noKey =
        "Не настроен ключ Яндекс.Карт (MapKit). Добавь ключ в настройках Джарвиса, раздел «Карты»."
    override val keyChanged =
        "Ключ Яндекс.Карт изменился. Полностью перезапусти приложение Джарвис — MapKit " +
            "нельзя переключить на новый ключ без перезапуска."
    override val locationDenied =
        "Не удалось определить, откуда строить маршрут: нет доступа к местоположению. " +
            "Укажи город в настройках или разреши доступ."
    override val locationUnavailable =
        "Не удалось определить твоё местоположение. Укажи город в настройках."
    override val placeNotFound = "Не нашёл такое место. Уточни название и попробуй снова."
    override val routeNotFound = "Не удалось построить маршрут. Проверь начальную и конечную точки."
    override val serviceFailed = "Сервис карт временно недоступен. Попробуй позже."
}

/** How many place candidates a search returns; a voice answer needs only a few. */
private const val SEARCH_LIMIT = 5

/**
 * How many route alternatives to request. More than one so an «а на автобусе?»
 * follow-up can be answered from the SAME call, but not so many that a
 * voice-first answer is overwhelmed.
 */
private const val ROUTE_ALTERNATIVES = 3

/**
 * `findPlace` (name preserved from the surface pin): find an organization,
 * address or place, or resolve a name to coordinates.
 *
 * The default-location policy is DELIBERATELY LENIENT: an unresolved location
 * degrades to an unconstrained search instead of an error, because a query
 * that already names a place («аптека в Москве») is still answerable — this is
 * the one case where a missing origin is not fatal.
 */
class GeoPlaceTool(
    private val client: GeoToolClient,
    private val resolver: LocationResolver,
    private val messages: GeoToolMessages,
    /**
     * Per-tool budget (registry default is 15 s): the first call may also
     * carry MapKit's native init. Value comes from
     * [com.jarvis.assistant.config.JarvisConfig.geoSearchTimeoutMs].
     */
    private val budgetMs: Long = 15_000,
) : ToolContract {
    override val name = "findPlace"
    override val description: String =
        "Find an organization, address or place on the map by name or a descriptive query. " +
            "Returns the matching places with their names, addresses and coordinates. Use it to " +
            "locate a specific place (a pharmacy, a shop, an address) or to turn a place name " +
            "into coordinates. The optional `near` hint biases the search toward a city or area."
    override val parametersJson = schema(
        mapOf(
            "query" to
                """{"type":"string","description":"What to find — an organization, address or """" +
                """place, e.g. 'аптека' or 'улица Тверская 1'. Required."}""",
            "near" to
                """{"type":"string","description":"City or area to search near, e.g. 'Москва'. """ +
                """OMIT to search near the user's default location."}""",
        ),
        required = listOf("query"),
    )

    /** MapKit's first call can include native init, so this needs its own budget. */
    override val timeoutMs: Long? = budgetMs

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments) ?: return JsonOut.error("Invalid JSON arguments")
        val query = args.string("query")?.trim().orEmpty()
        if (query.isEmpty()) return JsonOut.error("Missing required parameter: query")
        val nearArg = args.string("near")?.trim()?.takeIf { it.isNotEmpty() }

        return try {
            val near = searchOrigin(nearArg)
            when (val result = client.searchPlaces(query, near, SEARCH_LIMIT)) {
                is GeoResult.Ok ->
                    if (result.value.isEmpty()) {
                        JsonOut.error(messages.placeNotFound)
                    } else {
                        GeoJson.places(result.value)
                    }

                is GeoResult.Err ->
                    JsonOut.error(geoErrorText(messages, result.error, messages.placeNotFound))
            }
        } catch (e: CancellationException) {
            // Barge-in cancellation must propagate, never become a bogus error.
            throw e
        } catch (e: Exception) {
            // The client already converts MapKit failures into GeoResult, so an
            // exception reaching here is unexpected: log its TYPE only (never
            // the message, which may carry a URL or a query) and answer with
            // the generic service message.
            Timber.w("Geo tool call failed: %s", e::class.java.simpleName)
            JsonOut.error(messages.serviceFailed)
        }
    }

    /**
     * Best-effort origin for a search; null means "no `near` hint". An explicit
     * `near` that fails to geocode likewise degrades to null rather than
     * sinking an otherwise answerable query.
     */
    private suspend fun searchOrigin(nearArg: String?): GeoPoint? {
        if (nearArg != null) return firstPointOrNull(client.searchPlaces(nearArg, null, 1))
        return when (val outcome = resolver.resolve()) {
            is LocationOutcome.Resolved -> when (val location = outcome.location) {
                is ResolvedLocation.Coords -> GeoPoint(location.latitude, location.longitude)
                is ResolvedLocation.Place -> firstPointOrNull(client.searchPlaces(location.name, null, 1))
            }

            // No usable default location: search unconstrained and let the query
            // text carry the place. Failing here would make «аптека в Москве»
            // unanswerable for no reason.
            LocationOutcome.PermissionDenied, LocationOutcome.Unavailable -> null
        }
    }
}

/**
 * `getRoute` (name preserved from the surface pin): build a route between two
 * points.
 *
 * Unlike [GeoPlaceTool], a route GENUINELY needs an origin, so an unresolved
 * default location is an honest error rather than a silent unconstrained
 * attempt.
 *
 * No vehicle filtering is offered in v1: MapKit's `TransitOptions.avoid`
 * bitmask is derived from the UNVERIFIED `FilterVehicleTypes` enum mapping
 * (see MAPKIT_CONTRACT §5), so passing 0 avoids nothing and every mode is
 * reachable by simply choosing `mode` on a follow-up call.
 */
class GeoRouteTool(
    private val client: GeoToolClient,
    private val resolver: LocationResolver,
    private val messages: GeoToolMessages,
    /**
     * Per-tool budget (registry default is 15 s): a route request may also
     * carry MapKit's native init, and routing answers are slower than search.
     * Value comes from [com.jarvis.assistant.config.JarvisConfig.geoRouteTimeoutMs].
     */
    private val budgetMs: Long = 25_000,
) : ToolContract {
    override val name = "getRoute"
    override val description: String =
        // Deliberately does NOT promise an arrival time: MapKit leaves
        // TravelEstimation empty on this device (all six live routes had a null
        // arrival), so the model must not claim one. Duration + transfers +
        // leg detail are the fields that are actually populated.
        "Build a route between two points with Yandex Maps and return duration, transfers " +
            "and leg-by-leg details. `mode` is 'transit' (default) or 'walking'; " +
            "transit legs include the line name (bus/metro), the vehicle type, the transfer " +
            "point and the number of stops. `origin` defaults to the user's location when " +
            "omitted. For follow-ups like «а пешком?» or «а на автобусе?», call getRoute AGAIN " +
            "with the SAME destination and the new mode instead of asking the user to repeat it."
    override val parametersJson = schema(
        mapOf(
            "destination" to
                """{"type":"string","description":"Where to go — an address or place name, """ +
                """e.g. 'аэропорт Шереметьево'. Required."}""",
            "origin" to
                """{"type":"string","description":"Where to start from. OMIT to use the """ +
                """user's default location."}""",
            "mode" to
                """{"type":"string","enum":["transit","walking"],"description":"How to """ +
                """travel: 'transit' (public transport, default) or 'walking'."}""",
        ),
        required = listOf("destination"),
    )

    /** Native init + routing is slower than a plain search (config.geoRouteTimeoutMs). */
    override val timeoutMs: Long? = budgetMs

    override suspend fun execute(arguments: String): String {
        val args = ToolArgs.parse(arguments) ?: return JsonOut.error("Invalid JSON arguments")
        val destination = args.string("destination")?.trim().orEmpty()
        if (destination.isEmpty()) return JsonOut.error("Missing required parameter: destination")
        val originArg = args.string("origin")?.trim()?.takeIf { it.isNotEmpty() }
        // Unknown or missing mode degrades to transit (the common request)
        // rather than erroring — the model should not have to be exact here.
        val mode = when (args.string("mode")?.trim()?.lowercase()) {
            "walking" -> TravelMode.WALKING
            else -> TravelMode.TRANSIT
        }

        return try {
            val originPoint = when (val origin = resolveOrigin(originArg)) {
                is GeoResult.Ok -> origin.value
                is GeoResult.Err ->
                    return JsonOut.error(geoErrorText(messages, origin.error, messages.routeNotFound))
            }
            val destinationPoint = when (val dest = resolveDestination(destination, originPoint)) {
                is GeoResult.Ok -> dest.value
                is GeoResult.Err ->
                    return JsonOut.error(geoErrorText(messages, dest.error, messages.routeNotFound))
            }
            when (val routes = client.route(originPoint, destinationPoint, mode, ROUTE_ALTERNATIVES)) {
                is GeoResult.Ok ->
                    if (routes.value.isEmpty()) {
                        JsonOut.error(messages.routeNotFound)
                    } else {
                        GeoJson.route(originPoint, destinationPoint, mode, routes.value)
                    }

                is GeoResult.Err ->
                    JsonOut.error(geoErrorText(messages, routes.error, messages.routeNotFound))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The client already converts MapKit failures into GeoResult, so an
            // exception reaching here is unexpected: log its TYPE only (never
            // the message, which may carry a URL or a query) and answer with
            // the generic service message.
            Timber.w("Geo tool call failed: %s", e::class.java.simpleName)
            JsonOut.error(messages.serviceFailed)
        }
    }

    /** Explicit origin, else the shared default-location policy. Never null — a route needs one. */
    private suspend fun resolveOrigin(explicit: String?): GeoResult<GeoPoint> {
        if (explicit != null) return resolveDestination(explicit, near = null)
        return when (val outcome = resolver.resolve()) {
            is LocationOutcome.Resolved -> when (val location = outcome.location) {
                is ResolvedLocation.Coords -> GeoResult.Ok(GeoPoint(location.latitude, location.longitude))
                is ResolvedLocation.Place -> resolveDestination(location.name, near = null)
            }

            LocationOutcome.PermissionDenied -> GeoResult.Err(GeoError.PERMISSION_DENIED)
            LocationOutcome.Unavailable -> GeoResult.Err(GeoError.UNAVAILABLE)
        }
    }

    /** Geocode one place name to a point, preserving the client's typed error. */
    private suspend fun resolveDestination(query: String, near: GeoPoint?): GeoResult<GeoPoint> =
        when (val result = client.searchPlaces(query, near, 1)) {
            is GeoResult.Ok ->
                result.value.firstOrNull()?.point
                    ?.let { GeoResult.Ok(it) }
                    ?: GeoResult.Err(GeoError.NOT_FOUND)

            is GeoResult.Err -> result
        }
}

/** First hit's point, or null — used by the lenient [GeoPlaceTool] origin path. */
private fun firstPointOrNull(result: GeoResult<List<GeoPlace>>): GeoPoint? =
    (result as? GeoResult.Ok)?.value?.firstOrNull()?.point

/**
 * Shared [GeoError] -> user-facing text. `notFound` differs by tool (a place
 * search and a route have distinct "nothing found" answers), so it is passed
 * in rather than fixed here.
 */
private fun geoErrorText(messages: GeoToolMessages, error: GeoError, notFound: String): String =
    when (error) {
        GeoError.NO_KEY -> messages.noKey
        GeoError.KEY_CHANGED -> messages.keyChanged
        GeoError.PERMISSION_DENIED -> messages.locationDenied
        GeoError.UNAVAILABLE -> messages.locationUnavailable
        GeoError.NOT_FOUND -> notFound
        GeoError.FAILED -> messages.serviceFailed
    }
