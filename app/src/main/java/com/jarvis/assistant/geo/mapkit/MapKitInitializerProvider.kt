package com.jarvis.assistant.geo.mapkit

/**
 * Process-wide shared [MapKitInitializer] (GEO lane).
 *
 * WHY THIS EXISTS: `AppGraph` is constructed inside
 * `JarvisForegroundService.onStartCommand`, so it is rebuilt on EVERY service
 * start / restart / watchdog revive WITHIN THE SAME PROCESS. A fresh
 * `MapKitInitializer` per graph build would reset its `initializedKey` field to
 * null, so the next graph would call `bridge.setApiKey(...)` + `bridge.initialize(...)`
 * again — and MapKit permits `setApiKey` only ONCE per process (a second call
 * logs "API key is already set. Ignored." and can crash). The initializer's
 * set-once guard is INSTANCE state, so it cannot survive a graph rebuild; the
 * fix is to make the initializer itself process-scoped rather than graph-scoped.
 *
 * Mirrors [com.jarvis.assistant.tools.RingCoordinatorProvider]: a small object
 * memoizing ONE lazily-created instance, never rebuilt per call site.
 *
 * NOTE: unlike AlarmSchedulerProvider this provider has NO `install()` — there
 * is deliberately nothing for AppGraph to install, because a graph-owned
 * instance would reintroduce exactly the per-graph reset this guards against.
 */
object MapKitInitializerProvider {

    @Volatile
    private var instance: MapKitInitializer? = null

    /**
     * The one initializer for this process. Safe to call on every graph build:
     * the first call constructs it, every later call returns the same instance
     * (and therefore never re-runs `setApiKey`).
     */
    fun get(): MapKitInitializer {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: MapKitInitializer(DefaultMapKitFactoryBridge()).also { instance = it }
        }
    }

    /** Test seam: drop the memoized instance so a test can re-observe first-call behavior. */
    fun clearForTests() {
        instance = null
    }
}
