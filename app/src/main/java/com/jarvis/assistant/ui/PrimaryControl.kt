package com.jarvis.assistant.ui

/**
 * The home screen's primary (start / stop / resume) control, derived purely
 * from the three observable service flags. Extracted so the PRECEDENCE is
 * pinned by a plain JVM test: `userStopped` must outrank a still-bound running
 * graph.
 *
 * The bug this encodes: an explicit stop calls
 * `JarvisForegroundService.explicitStop()`, which sets
 * `AppPrefs.userStopped = true` and suppresses the watchdog revive. While the
 * graph is still bound (or when the assistant is running-but-deaf), the old
 * first branch `GraphHolder.isRunning -> stop` showed «Остановить» over a
 * service that would never auto-revive — leaving no way back to listening.
 * Resume therefore has the highest priority; the ONLY code that clears the flag
 * is an explicit start.
 */
object PrimaryControl {

    /** The four mutually exclusive states the single button can present. */
    enum class State {
        /** Explicitly stopped (or deaf with `userStopped=true`): offer resume. */
        RESUMED,

        /** Live graph: offer stop. */
        STOPPABLE,

        /** Service attached but no graph yet: disabled "starting up". */
        BOOTSTRAPPING,

        /** No service: offer start. */
        STARTABLE,
    }

    /**
     * Precedence, highest first:
     * 1. [userStopped] — the flag suppresses auto-revive, so resume must win
     *    even when a graph is still bound.
     * 2. [running] — a live graph with no stop flag can be stopped.
     * 3. [serviceAttached] — bootstrapping (disabled no-op).
     * 4. otherwise — startable.
     */
    fun state(userStopped: Boolean, running: Boolean, serviceAttached: Boolean): State = when {
        userStopped -> State.RESUMED
        running -> State.STOPPABLE
        serviceAttached -> State.BOOTSTRAPPING
        else -> State.STARTABLE
    }
}
