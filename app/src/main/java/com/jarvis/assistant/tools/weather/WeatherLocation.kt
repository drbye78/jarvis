package com.jarvis.assistant.tools.weather

import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Where a weather query points. Either an explicit/configured place name (which
 * the Open-Meteo client geocodes) or raw coordinates from the device GPS.
 *
 * [Coords.label] is what the assistant may SAY for a position it cannot name:
 * Open-Meteo has no reverse geocoding and we deliberately add no third-party
 * egress, so a GPS fix is reported as «текущее местоположение» rather than a
 * city (see ARCHITECTURE.md, privacy/egress notes).
 */
sealed interface WeatherLocation {
    data class Place(val name: String) : WeatherLocation
    data class Coords(
        val latitude: Double,
        val longitude: Double,
        val label: String,
    ) : WeatherLocation
}

/** A resolved weather request: where + how many forecast days (1..7). */
data class WeatherQuery(val location: WeatherLocation, val days: Int)

/** A device position. [ageMs] is how stale the fix is; [source] is e.g. "gps". */
data class LocationFix(
    val latitude: Double,
    val longitude: Double,
    val ageMs: Long,
    val source: String?,
)

/**
 * Device-location seam. Android-free so the resolver is JVM-testable with a
 * fake; the only implementation that touches `LocationManager` is
 * [AndroidLocationProvider]. GMS-free app: no FusedLocationProviderClient.
 */
interface LocationProvider {
    /** Fine OR coarse granted. Checked live — the user can revoke anytime. */
    fun hasPermission(): Boolean

    /**
     * Best effort fix, bounded. Returns a fix no older than [maxAgeMs] (a
     * fresh one-shot, or a still-fresh last-known), else null. Never throws
     * for a missing/late/unavailable provider; cancellation propagates.
     */
    suspend fun getFix(maxAgeMs: Long, timeoutMs: Long): LocationFix?
}

/** Outcome of resolving the default location — explicit, never an exception. */
sealed interface LocationOutcome {
    data class Resolved(val location: WeatherLocation) : LocationOutcome

    /** No configured location and the user has not granted location access. */
    data object PermissionDenied : LocationOutcome

    /** Permission granted, but no usable fix (no hardware/provider, or timeout). */
    data object Unavailable : LocationOutcome
}

/** Resolves the location used when the model omits an explicit one. */
interface WeatherLocationResolver {
    suspend fun resolve(): LocationOutcome
}

/**
 * Default precedence (owner decision): a **configured** location always wins —
 * no GPS, no permission needed. Only when it is blank do we fall back to a
 * device fix, and every failure degrades to a typed [LocationOutcome] so the
 * tool can answer honestly instead of inventing a city.
 *
 * This matters more than usual on the target device: it is GMS-free and often
 * WiFi-only, so `NETWORK_PROVIDER` frequently yields nothing and GPS hardware
 * may be absent — the configured location is the realistic primary path.
 */
class DefaultWeatherLocationResolver(
    /** Live read of the Settings value; blank ⇒ auto-detect. */
    private val configuredLocation: () -> String,
    private val provider: LocationProvider,
    /** Localized label for an unnamed GPS position. */
    private val coordsLabel: () -> String,
    private val maxAgeMs: Long,
    private val timeoutMs: Long,
) : WeatherLocationResolver {

    override suspend fun resolve(): LocationOutcome {
        val configured = configuredLocation().trim()
        if (configured.isNotEmpty()) return LocationOutcome.Resolved(WeatherLocation.Place(configured))

        if (!provider.hasPermission()) return LocationOutcome.PermissionDenied

        val fix = try {
            provider.getFix(maxAgeMs, timeoutMs)
        } catch (e: CancellationException) {
            throw e // barge-in must propagate
        } catch (e: Exception) {
            // An unavailable/broken provider is "no fix", not a crash — but log
            // it so a silently-degrading location lane is diagnosable.
            Timber.w(e, "Weather location provider failed; treating as no fix")
            null
        } ?: return LocationOutcome.Unavailable

        return LocationOutcome.Resolved(
            WeatherLocation.Coords(fix.latitude, fix.longitude, coordsLabel()),
        )
    }
}
