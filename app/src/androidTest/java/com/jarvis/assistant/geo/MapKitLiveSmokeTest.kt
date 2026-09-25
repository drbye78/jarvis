package com.jarvis.assistant.geo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.geo.mapkit.MapKitInitResult
import com.jarvis.assistant.geo.mapkit.MapKitInitializerProvider
import com.jarvis.assistant.geo.mapkit.YandexMapKitGeoClient
import com.jarvis.assistant.util.CredentialsStore
import com.yandex.mapkit.RequestPoint
import com.yandex.mapkit.RequestPointType
import com.yandex.mapkit.geometry.Point
import com.yandex.mapkit.transport.TransportFactory
import com.yandex.mapkit.transport.masstransit.FitnessOptions
import com.yandex.mapkit.transport.masstransit.Route
import com.yandex.mapkit.transport.masstransit.RouteOptions
import com.yandex.mapkit.transport.masstransit.Session
import com.yandex.mapkit.transport.masstransit.TimeOptions
import com.yandex.mapkit.transport.masstransit.TransitOptions
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DEVICE-ONLY live smoke coverage for the geography lane. Not part of the CI
 * gate (that job only compiles this source set); every test SKIPS unless a
 * MapKit key has been entered in Settings → «Карты», so no secret ever lives in
 * the repo.
 *
 * Why this tier and not `src/test`: MapKit 4.45.0 ships **Java 21** bytecode
 * while the JVM test runtime is Java 17, so no MapKit class can be loaded in a
 * unit test (`UnsupportedClassVersionError`). Android runs dex, where that
 * limit does not exist — so this is the ONLY place the real SDK path can be
 * exercised: native `.so` load, the `X-YMapKit-Api-Key` handshake, live search,
 * and live masstransit routing with the line/transfer detail that motivated
 * choosing MapKit over the HTTP Maps APIs.
 *
 * Run:
 * ```
 * ./gradlew :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w -e class com.jarvis.assistant.geo.MapKitLiveSmokeTest \
 *   com.jarvis.assistant.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class MapKitLiveSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** The configured key, or null when Settings → «Карты» is still empty. */
    private fun configuredKey(): String? =
        CredentialsStore.init(context).mapKitApiKey.trim().takeIf { it.isNotEmpty() }

    private fun client(key: String): YandexMapKitGeoClient =
        YandexMapKitGeoClient(
            context,
            MapKitInitializerProvider.get(),
            apiKey = { key },
        )

    /** Moscow centre — a fixed origin so the smoke does not need location access. */
    private val moscow = GeoPoint(55.7558, 37.6173)

    /**
     * ~10 km west across Moscow. Chosen deliberately: the earlier
     * Moscow -> Sheremetyevo fixture is NOT transit-serviceable and MapKit
     * answers it with an empty list (verified on-device 2026-09-25), which says
     * nothing about the mapper. This hop yields multi-leg rides.
     */
    private val transitDestination = GeoPoint(55.7100, 37.5000)

    @Test
    fun liveInit_reportsReadyWithTheConfiguredKey() {
        val key = configuredKey()
        assumeTrue("no MapKit key configured in Settings → «Карты»", key != null)

        val result = runBlocking {
            MapKitInitializerProvider.get().ensureInitialized(context, key!!)
        }
        println("MAPKIT_INIT=$result")

        assertTrue("expected Ready, got $result", result is MapKitInitResult.Ready)
    }

    @Test
    fun liveSearch_findsAnOrganization() {
        val key = configuredKey()
        assumeTrue("no MapKit key configured in Settings → «Карты»", key != null)

        val result = runBlocking { client(key!!).searchPlaces("аптека", moscow, 3) }
        println("MAPKIT_SEARCH=$result")

        assertTrue("expected Ok, got $result", result is GeoResult.Ok)
        val places = (result as GeoResult.Ok).value
        println("MAPKIT_SEARCH_JSON=${GeoJson.places(places)}")
        assertTrue("expected at least one place", places.isNotEmpty())
        assertTrue(
            "a search hit must carry a name; got ${places.first()}",
            places.first().name.isNotBlank(),
        )
    }

    @Test
    fun liveTransitRoute_exposesLineNamesAndTransfers() {
        val key = configuredKey()
        assumeTrue("no MapKit key configured in Settings → «Карты»", key != null)

        val result = runBlocking {
            client(key!!).route(moscow, transitDestination, TravelMode.TRANSIT, 3)
        }
        println("MAPKIT_ROUTE=$result")

        assertTrue("expected Ok, got $result", result is GeoResult.Ok)
        val routes = (result as GeoResult.Ok).value
        println("MAPKIT_ROUTE_JSON=${GeoJson.route(moscow, transitDestination, TravelMode.TRANSIT, routes)}")
        assertTrue("expected at least one route", routes.isNotEmpty())

        // The WHOLE reason MapKit was chosen over the HTTP Maps APIs: an HTTP
        // transit route returns only duration/distance. If the mapper is reading
        // the SDK correctly, at least one leg names the transport.
        val transports = routes.flatMap { it.legs }.filterIsInstance<GeoLeg.Transport>()
        assertTrue(
            "transit legs must name the line — an answer without line names means " +
                "the mapper is not reading Line metadata; legs=" +
                routes.flatMap { it.legs },
            transports.isNotEmpty(),
        )
        assertTrue(
            "a live line name must not be blank",
            transports.any { it.line.isNotBlank() },
        )
    }

    @Test
    fun liveWalkingRoute_usesThePedestrianRouter() {
        val key = configuredKey()
        assumeTrue("no MapKit key configured in Settings → «Карты»", key != null)

        // A short hop so walking is genuinely plausible.
        val nearby = GeoPoint(55.7601, 37.6183)
        val result = runBlocking {
            client(key!!).route(moscow, nearby, TravelMode.WALKING, 2)
        }
        println("MAPKIT_WALK=$result")

        assertTrue("expected Ok, got $result", result is GeoResult.Ok)
        val routes = (result as GeoResult.Ok).value
        assertTrue("expected at least one walking route", routes.isNotEmpty())
        assertTrue(
            "a walking route must not contain transport legs",
            routes.flatMap { it.legs }.none { it is GeoLeg.Transport },
        )
    }

    /**
     * Smoke-checklist item 6: after the key is changed in Settings, the next geo
     * call must report [MapKitInitResult.KeyChanged] rather than crash or silently
     * keep using the old key (MapKit cannot be re-keyed inside a process).
     *
     * Safe by construction: only the REAL key is ever handed to a successful
     * init. The differing key is compared and rejected BEFORE the bridge is
     * touched, so this can never poison the other tests in this process.
     */
    @Test
    fun aChangedKeyIsReportedNotApplied() {
        val key = configuredKey()
        assumeTrue("no MapKit key configured in Settings → «Карты»", key != null)

        val initializer = MapKitInitializerProvider.get()
        val first = runBlocking { initializer.ensureInitialized(context, key!!) }
        assertTrue("expected the real key to initialize, got $first", first is MapKitInitResult.Ready)

        val changed = runBlocking { initializer.ensureInitialized(context, "$key-changed") }
        println("MAPKIT_KEYCHANGED=$changed")
        assertTrue(
            "a changed key must be reported as KeyChanged, got $changed",
            changed is MapKitInitResult.KeyChanged,
        )

        // The original key must still be the one in effect.
        val stillReady = runBlocking { initializer.ensureInitialized(context, key!!) }
        assertTrue("the original key must remain in effect, got $stillReady", stillReady is MapKitInitResult.Ready)
    }

    /**
     * Pins the transit ETA situation, which the docs call out: MapKit does NOT
     * populate `TravelEstimation` for masstransit routes on this device — the
     * object itself is absent (`route.estimation=false`, `section.estimation=false`),
     * not merely its `text`. The mapper therefore reads `.text` of an absent
     * object and correctly yields null, and `getRoute`'s description must not
     * promise an arrival time.
     *
     * The assertion is deliberately the INVARIANT (the request succeeds; nothing
     * throws), not a hard `arrival == null`, so this keeps passing if Yandex ever
     * starts returning ETAs — which would be an improvement, not a regression.
     * The raw fields are printed so a future run can tell which way it went.
     */
    @Test
    fun diagnostic_rawArrivalFields() {
        val key = configuredKey()
        assumeTrue("no MapKit key configured in Settings → «Карты»", key != null)
        val initialized = runBlocking { MapKitInitializerProvider.get().ensureInitialized(context, key!!) }
        assumeTrue("MapKit not initialized: $initialized", initialized is MapKitInitResult.Ready)

        val raw = runBlocking {
            withContext(Dispatchers.Main.immediate) {
                withTimeoutOrNull(25_000) {
                    suspendCancellableCoroutine<String> { cont ->
                        val done = AtomicBoolean(false)
                        val router = TransportFactory.getInstance().createMasstransitRouter()
                        val points = listOf(moscow, transitDestination).map {
                            RequestPoint(Point(it.latitude, it.longitude), RequestPointType.WAYPOINT, null, null, null)
                        }
                        val listener = object : Session.RouteListener {
                            override fun onMasstransitRoutes(routes: List<Route>) {
                                if (done.compareAndSet(false, true) && cont.isActive) {
                                    val route = routes.firstOrNull()
                                    val est = route?.metadata?.estimation
                                    val sEst = route?.sections?.firstOrNull()?.metadata?.estimation
                                    cont.resume(
                                        "route.estimation=${est != null} " +
                                            "dep=${est?.departureTime?.value}/${est?.departureTime?.text} " +
                                            "arr=${est?.arrivalTime?.value}/${est?.arrivalTime?.text} | " +
                                            "section.estimation=${sEst != null} " +
                                            "arr=${sEst?.arrivalTime?.value}/${sEst?.arrivalTime?.text}",
                                    )
                                }
                            }

                            override fun onMasstransitRoutesError(error: com.yandex.runtime.Error) {
                                if (done.compareAndSet(false, true) && cont.isActive) cont.resume("ERROR $error")
                            }
                        }
                        val session = router.requestRoutes(
                            points,
                            TransitOptions(0, TimeOptions()),
                            RouteOptions(FitnessOptions()),
                            listener,
                        )
                        cont.invokeOnCancellation { runCatching { session.cancel() } }
                    }
                } ?: "TIMEOUT"
            }
        }
        println("MAPKIT_RAW_ARRIVAL=$raw")
        assertTrue(
            "the transit request must complete without error or timeout, got: $raw",
            raw != "TIMEOUT" && !raw.startsWith("ERROR"),
        )
    }
}
