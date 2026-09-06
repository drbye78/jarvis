package com.jarvis.assistant.di

import com.jarvis.assistant.service.JarvisForegroundService

/**
 * Process-wide holder giving UI components access to the live [AppGraph]
 * (state machine, conversation transcript). The graph itself is owned by the
 * foreground service; the holder just exposes a nullable reference.
 *
 * [service] mirrors the live service instance (set in onCreate, cleared in
 * onDestroy) for the rare call sites that must reach the SERVICE rather than
 * the graph — e.g. the mediaProjection FGS-type promotion before playback
 * capture starts (Settings → AEC diagnostics).
 */
object GraphHolder {
    @Volatile
    var graph: AppGraph? = null

    @Volatile
    var service: JarvisForegroundService? = null

    val isRunning: Boolean get() = graph != null
}
