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
 * - `initialize()` MUST run on the **UI thread**: the native runtime rejects any
 *   other thread with "Runtime could only be initialized from ui thread"
 *   (VERIFIED on-device 2026-09-25). An earlier revision ran this on
 *   `Dispatchers.IO` on the wrong assumption that the native load had to stay
 *   off the main thread to avoid an ANR — the device smoke caught it. Hence
 *   [mainDispatcher]; the cost is a one-time native load on the UI thread.
 * - `setLocale` / `setApiKey` / `initialize` must run in that order, and
 *   `setApiKey` may be called only ONCE per process (a second call logs
 *   "already set" and can crash).
 * - `AppGraph` is rebuilt on EVERY service start, so init can NOT live there;
 *   this initializer is the single owner of "have we initialized, and with
 *   which key?" and serializes concurrent attempts behind a [Mutex].
 */
class MapKitInitializer(
    private val bridge: MapKitFactoryBridge,
    /**
     * Test seam, mirroring `HybridWakeWordDetector.engineBuildDispatcher`.
     * NULL means "resolve the UI dispatcher at use time". It is deliberately not
     * a direct `Dispatchers.Main.immediate` default: that would be evaluated at
     * CONSTRUCTION, so merely building the initializer (e.g. the identity test
     * for [MapKitInitializerProvider], or any DI wiring) would touch
     * `Dispatchers.Main` and throw on a JVM with no Android main looper.
     */
    private val mainDispatcher: CoroutineDispatcher? = null,
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
                // Resolved at use time: a null seam means the real UI thread.
                val dispatcher = mainDispatcher ?: Dispatchers.Main.immediate
                withContext(dispatcher) {
                    // Order is load-bearing (mapkit-android-demo#221): locale,
                    // then key, then initialize.
                    bridge.setLocale(LOCALE)
                    bridge.setApiKey(normalizedKey)
                    bridge.initialize(context)
                    // `onStart()` is a foreground notification, NOT the request
                    // pipeline: `initialize()` already ran Runtime.init + set the
                    // key. But upstream documents the rule for LATE initialization
                    // (anything other than Application.onCreate — exactly our lazy
                    // Service path): "if you initialize MapKit in a method
                    // different from didFinishLaunching, call onStart() after".
                    // So call it once here, and NEVER call onStop(): this is an
                    // always-on assistant, and with no MapView a "backgrounded"
                    // state would only risk stalling an in-flight request.
                    bridge.onStart()
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
