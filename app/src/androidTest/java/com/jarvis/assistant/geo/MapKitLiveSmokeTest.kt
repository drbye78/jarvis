package com.jarvis.assistant.geo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.geo.mapkit.MapKitInitResult
import com.jarvis.assistant.geo.mapkit.MapKitInitializerProvider
import com.jarvis.assistant.geo.mapkit.YandexMapKitGeoClient
import com.jarvis.assistant.util.CredentialsStore
import kotlinx.coroutines.runBlocking
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
}
