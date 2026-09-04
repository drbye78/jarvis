package com.jarvis.assistant.tools

/**
 * Pure decision core for the openApp tool's honesty contract (audit A5).
 *
 * Android 10+ silently blocks `startActivity` from a context with no visible
 * window (a foreground service is NOT an exemption) — and the call returns
 * without throwing, so the tool cannot tell "opened" from "swallowed" after
 * the fact. The ONLY signal available is whether Jarvis's own UI is visible
 * ([com.jarvis.assistant.media.AppForegroundTracker]): with a visible window
 * the launch is BAL-permitted and can be reported as done; without one the
 * outcome is honestly phrased as an attempt with a contingency instruction —
 * the same standard the music cascade already applies to cold starts.
 */
enum class OpenAppOutcome {
    /** Jarvis UI was visible → the launch is BAL-permitted → report "ok". */
    OK,

    /** No visible window → the system may have swallowed the launch → report "attempted". */
    ATTEMPTED;

    companion object {
        fun of(uiVisible: Boolean): OpenAppOutcome = if (uiVisible) OK else ATTEMPTED
    }
}
