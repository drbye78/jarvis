package com.jarvis.assistant.geo.mapkit

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of [MapKitInitializer.ensureInitialized]. */
sealed interface MapKitInitResult {
    /** The SDK is initialized (with this key). */
    data object Ready : MapKitInitResult

    /** No key is configured — the SDK was NOT touched. */
    data object NoKey : MapKitInitResult

    /**
     * The SDK is already live under a DIFFERENT key. MapKit cannot be re-keyed
     * in-process, so the caller must restart the process to apply the new key.
     */
    data object KeyChanged : MapKitInitResult

    /** The native SDK threw; MapKit is unusable for this turn. */
    data class Failed(val detail: String?) : MapKitInitResult
}

/**
 * Lazy, process-wide MapKit bootstrap.
 *
 * MapKit's init rules are unforgiving and invisible to the JVM suite unless
 * they are isolated here:
 * - `initialize()` loads native libraries → MUST NOT run on the main thread
 *   (the app targets a Kirin 710A-class tablet; an ANR here is unacceptable),
 *   hence [ioDispatcher].
 * - `setLocale` / `setApiKey` / `initialize` must run in that order, and
 *   `setApiKey` may be called only ONCE per process (a second call logs
 *   "already set" and can crash).
 * - `AppGraph` is rebuilt on EVERY service start, so init can NOT live there;
 *   this initializer is the single owner of "have we initialized, and with
 *   which key?" and serializes concurrent attempts behind a [Mutex].
 */
class MapKitInitializer(
    private val bridge: MapKitFactoryBridge,
    /** Test seam, mirroring `HybridWakeWordDetector.engineBuildDispatcher`. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    /** Key the SDK was last successfully initialized with; null = not yet. */
    private var initializedKey: String? = null

    /**
     * Ensures MapKit is initialized with [key], at most once per process.
     *
     * Returns [MapKitInitResult.Ready] when already initialized with the same
     * key (the bridge is NOT called again), [MapKitInitResult.KeyChanged] when
     * a different key is now configured, [MapKitInitResult.NoKey] when [key] is
     * blank, and [MapKitInitResult.Failed] when the native SDK throws. No
     * throwable from the bridge ever escapes; cancellation is rethrown.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun ensureInitialized(context: Context, key: String): MapKitInitResult =
        mutex.withLock {
            val normalizedKey = key.trim()
            val currentKey = initializedKey
            if (currentKey != null) {
                // Initialized state dominates: once native is live, the only
                // honest answers are "same key" or "key changed".
                return@withLock if (currentKey == normalizedKey) {
                    MapKitInitResult.Ready
                } else {
                    MapKitInitResult.KeyChanged
                }
            }

            if (normalizedKey.isEmpty()) return@withLock MapKitInitResult.NoKey

            try {
                withContext(ioDispatcher) {
                    // Order is load-bearing (mapkit-android-demo#221): locale,
                    // then key, then initialize.
                    bridge.setLocale(LOCALE)
                    bridge.setApiKey(normalizedKey)
                    bridge.initialize(context)
                }
                initializedKey = normalizedKey
                MapKitInitResult.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Native boundary: MapKit surfaces arbitrary RuntimeException
                // and UnsatisfiedLinkError shapes. A leak here would crash the
                // service, so the catch stays deliberately broad — the same
                // rule (and suppression) as AudioRecordSource's native teardown.
                // The key is NOT remembered: a retry may still succeed.
                MapKitInitResult.Failed(e.message)
            }
        }

    companion object {
        /**
         * Fixed locale. MapKit's locale is process-global and setting it per
         * call would race the service and UI lanes; the app is ru-RU only.
         */
        private const val LOCALE = "ru_RU"
    }
}
