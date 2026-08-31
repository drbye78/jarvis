package com.jarvis.assistant.media

/**
 * M2: process-foreground tracker for the MUSIC lane.
 *
 * Android 10+ silently blocks `startActivity` from an app that has no visible
 * window and no background-activity-launch (BAL) exemption — a foreground
 * service alone is NOT an exemption. `startActivity` from our FGS therefore
 * returns without throwing even when the launch was blocked, which made the
 * music cascade claim «открыл поиск» for a deep link that never appeared.
 *
 * [JarvisApplication] (see the wiring there) increments/decrements
 * [startedActivities] via [android.app.Application.ActivityLifecycleCallbacks]
 * — the same counting ProcessLifecycleOwner does. [AndroidMediaGateway]
 * exposes the result through [MediaGateway.isUiVisible], and the orchestrator
 * phrases launch outcomes as *attempts* while the UI is hidden, instead of
 * asserting success it cannot verify.
 *
 * Volatile because the counter is written on the main thread and read from
 * the session coroutines.
 */
object AppForegroundTracker {
    @Volatile
    var startedActivities: Int = 0
        private set

    val isVisible: Boolean get() = startedActivities > 0

    fun onActivityStarted() {
        startedActivities += 1
    }

    fun onActivityStopped() {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
    }
}
