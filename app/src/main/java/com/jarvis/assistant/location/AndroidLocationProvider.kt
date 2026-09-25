package com.jarvis.assistant.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * The one Android-aware [LocationProvider]: framework `LocationManager` only —
 * this app is GMS-free, so `FusedLocationProviderClient` is not an option.
 *
 * It never prompts (the caller is a foreground-service turn, not an Activity):
 * [hasPermission] is a live read and the Settings dialog is another lane's job.
 * It also never throws for a missing/late/unavailable provider — every such
 * case degrades to `null` (or a stale last-known) so a weather turn stays
 * honest instead of crashing; [CancellationException] always propagates so a
 * barge-in is never swallowed.
 *
 * [ioDispatcher] is injectable only so the acquisition path can be exercised
 * under a test dispatcher; production uses [Dispatchers.IO].
 */
@SuppressLint("MissingPermission") // hasPermission() is checked in acquireFix before any provider call
class AndroidLocationProvider(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LocationProvider {

    private val appContext = context.applicationContext

    /** `as?` (not `as`) — odd OEM ROMs can hand back null; then we have no fix. */
    private val locationManager: LocationManager? =
        appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun getFix(maxAgeMs: Long, timeoutMs: Long): LocationFix? =
        withContext(ioDispatcher) {
            try {
                acquireFix(maxAgeMs, timeoutMs)
            } catch (e: CancellationException) {
                throw e // barge-in
            } catch (e: Exception) {
                Timber.w(e, "Location fix acquisition failed")
                null
            }
        }

    private suspend fun acquireFix(maxAgeMs: Long, timeoutMs: Long): LocationFix? {
        val lm = locationManager ?: return null
        if (!hasPermission()) return null

        // Fast path: a still-fresh last-known needs no waiting.
        lastKnownFix(lm, maxAgeMs)?.let { return it }

        withTimeoutOrNull(timeoutMs) { awaitSingleFix(lm) }?.let { return it }

        // Timeout with no fresh fix. Decision (owner-accepted trade-off): return
        // a stale last-known when one exists, carrying its TRUE ageMs (which may
        // exceed maxAgeMs) so the caller can see it is stale; when nothing is
        // cached at all, return null instead of inventing a position.
        return lastKnownFix(lm, Long.MAX_VALUE)
    }

    /**
     * One fresh fix from the first provider that exists, preferring GPS over
     * network. A provider that is absent is skipped; if none exist, null.
     */
    private suspend fun awaitSingleFix(lm: LocationManager): LocationFix? {
        for (provider in REQUEST_PROVIDERS) {
            if (providerExists(lm, provider)) {
                val location = requestSingleFix(lm, provider)
                if (location != null) return location.toFix(ageMs(location))
            }
        }
        return null
    }

    private suspend fun requestSingleFix(lm: LocationManager, provider: String): Location? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            awaitCurrentLocation(lm, provider)
        } else {
            awaitSingleUpdate(lm, provider)
        }

    /** API 30+: the modern one-shot, cancelled through [CancellationSignal]. */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun awaitCurrentLocation(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            try {
                lm.getCurrentLocation(provider, signal, appContext.mainExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "getCurrentLocation failed for %s", provider)
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * API 29 fallback: [LocationManager.getCurrentLocation] does not exist until
     * API 30, so the deprecated `requestSingleUpdate` is the only safe one-shot.
     * The listener is removed on both delivery and cancellation (the coroutine
     * equivalent of a `finally`) so a pending update cannot leak past a turn.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private suspend fun awaitSingleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    lm.removeUpdates(this) // one-shot: stop after the first fix
                    if (cont.isActive) cont.resume(location)
                }

                override fun onProviderDisabled(provider: String) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            }
            cont.invokeOnCancellation { lm.removeUpdates(listener) }
            try {
                lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            } catch (e: Exception) {
                lm.removeUpdates(listener)
                Timber.d(e, "requestSingleUpdate failed for %s", provider)
                if (cont.isActive) cont.resume(null)
            }
        }

    /** Newest acceptable last-known across GPS, network and passive. */
    private fun lastKnownFix(lm: LocationManager, maxAgeMs: Long): LocationFix? {
        for (provider in LAST_KNOWN_PROVIDERS) {
            val location = try {
                lm.getLastKnownLocation(provider)
            } catch (e: Exception) {
                // Absent provider — common on this GMS-free, often WiFi-only device.
                Timber.d(e, "No last-known location for provider %s", provider)
                null
            } ?: continue
            val age = ageMs(location)
            if (age <= maxAgeMs) return location.toFix(age)
        }
        return null
    }

    private fun providerExists(lm: LocationManager, provider: String): Boolean =
        lm.allProviders.contains(provider)

    /**
     * Age from the monotonic clock. `Location.time` is wall-clock and can jump;
     * `elapsedRealtimeNanos` cannot.
     */
    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtime() - location.elapsedRealtimeNanos / NANOS_PER_MILLI)
            .coerceAtLeast(0L)

    private fun Location.toFix(age: Long): LocationFix =
        LocationFix(latitude, longitude, age, provider)

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Fresh requests: passive cannot be requested, only read as last-known. */
        val REQUEST_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )

        val LAST_KNOWN_PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )
    }
}
