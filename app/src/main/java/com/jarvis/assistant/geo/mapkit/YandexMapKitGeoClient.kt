package com.jarvis.assistant.geo.mapkit

import android.content.Context
import com.jarvis.assistant.geo.GeoError
import com.jarvis.assistant.geo.GeoPlace
import com.jarvis.assistant.geo.GeoPoint
import com.jarvis.assistant.geo.GeoResult
import com.jarvis.assistant.geo.GeoRoute
import com.jarvis.assistant.geo.GeoToolClient
import com.jarvis.assistant.geo.TravelMode
import kotlinx.coroutines.CancellationException

/**
 * The single [GeoToolClient] implementation, backed by Yandex MapKit.
 *
 * Every call first runs [MapKitInitializer.ensureInitialized] and maps its
 * outcome to a typed [GeoError] — a missing or changed key never reaches the
 * native SDK. The initializer (not this class, not AppGraph) owns the
 * once-per-process `setApiKey`: `AppGraph` is rebuilt on every service start,
 * so wiring init there would call `setApiKey` twice and crash.
 *
 * This class never throws: every failure becomes [GeoResult.Err]. A MapKit
 * `Error` is converted inside [MapKitSearch]/[MapKitRouting], so no MapKit
 * type escapes this package.
 */
class YandexMapKitGeoClient(
    private val context: Context,
    private val initializer: MapKitInitializer,
    /** Live reader: the key may be entered in Settings after construction. */
    private val apiKey: () -> String,
    private val search: MapKitSearch = MapKitSearch(),
    private val routing: MapKitRouting = MapKitRouting(),
) : GeoToolClient {

    override suspend fun searchPlaces(
        query: String,
        near: GeoPoint?,
        limit: Int,
    ): GeoResult<List<GeoPlace>> {
        val text = query.trim()
        if (text.isEmpty()) return GeoResult.Err(GeoError.NOT_FOUND)
        return guarded {
            val places = search.forward(text, near, limit)
            if (places.isEmpty()) GeoResult.Err(GeoError.NOT_FOUND) else GeoResult.Ok(places)
        }
    }

    override suspend fun resolveLabel(point: GeoPoint): GeoResult<String> = guarded {
        val label = search.reverse(point)
        if (label.isNullOrBlank()) GeoResult.Err(GeoError.NOT_FOUND) else GeoResult.Ok(label)
    }

    override suspend fun route(
        origin: GeoPoint,
        destination: GeoPoint,
        mode: TravelMode,
        alternatives: Int,
    ): GeoResult<List<GeoRoute>> = guarded {
        // `alternatives` is a hint; MapKit returns what its routers decide.
        val routes = MapKitRouteMapper.map(routing.routes(origin, destination, mode))
        if (routes.isEmpty()) GeoResult.Err(GeoError.NOT_FOUND) else GeoResult.Ok(routes)
    }

    /**
     * Gates on MapKit init, then runs [block]. The catch is deliberately broad
     * ([TooGenericExceptionCaught]): this is the native boundary, where MapKit
     * can surface arbitrary RuntimeException/Error shapes, and the contract is
     * "never crash the caller".
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> guarded(block: suspend () -> GeoResult<T>): GeoResult<T> =
        when (val init = initializer.ensureInitialized(context, apiKey())) {
            MapKitInitResult.Ready -> try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: MapKitFailure) {
                GeoResult.Err(e.error, e.detail)
            } catch (e: Throwable) {
                GeoResult.Err(GeoError.FAILED, e.message)
            }

            MapKitInitResult.NoKey -> GeoResult.Err(GeoError.NO_KEY)
            MapKitInitResult.KeyChanged -> GeoResult.Err(GeoError.KEY_CHANGED)
            is MapKitInitResult.Failed -> GeoResult.Err(GeoError.FAILED, init.detail)
        }
}
