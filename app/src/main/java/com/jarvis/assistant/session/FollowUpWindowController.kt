package com.jarvis.assistant.session

/**
 * Pure follow-up-window controller — the decision core of "continue the
 * dialogue without repeating the wake word".
 *
 * One window per SPOKEN turn: after the assistant's TTS drains (not after a
 * barge-in'd/cancelled turn), the mic window opens for [windowMs]; speech
 * onset (EnergyVad) inside the window triggers a new session immediately;
 * silence lets the window expire. Every subsequent spoken turn opens a new
 * window — continuous conversation as long as the user keeps talking.
 *
 * The controller OWNS no coroutines, no clocks, no audio: [onTurnEnded] /
 * [onVadActive] / [onWakeWord] / [onCancelled] are events, and
 * [transition] maps the internal state onto outputs the SessionManager
 * applies. Time is injected ([nowMs]) so JVM tests drive the clock.
 */
class FollowUpWindowController(
    private var windowMs: Long = DEFAULT_WINDOW_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    enum class ControllerState { IDLE, OPEN }

    /** Commands the SessionManager must apply. */
    sealed interface Effect {
        /** Enter FOLLOW_UP_WINDOW state now; UI shows the countdown orb. */
        data object OpenWindow : Effect

        /** VAD onset inside the window — start a session WITHOUT wake word. */
        data object StartFollowUpTurn : Effect

        /** Window elapsed with no speech — fall back to IDLE. */
        data object ExpireWindow : Effect
    }

    var state: ControllerState = ControllerState.IDLE
        private set

    /** Window deadline while OPEN (ms, [nowMs] time base). */
    var deadlineMs: Long = Long.MIN_VALUE
        private set

    /** Fraction of the window remaining (0..1); UI countdown. */
    fun remainingFraction(): Float {
        if (state != ControllerState.OPEN) return 0f
        val now = nowMs()
        val remain = (deadlineMs - now).coerceAtLeast(0)
        val window = windowMs.coerceAtLeast(1)
        return (remain.toDouble() / window).coerceIn(0.0, 1.0).toFloat()
    }

    fun setWindowMs(value: Long) {
        windowMs = value.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
    }

    /**
     * A turn ended. [spoke] = at least one TTS sentence reached playback and
     * the drain completed normally (not barge-in cancelled). Enabling is the
     * caller's decision (config/prefs) — this controller assumes it was told
     * to run only when the feature is on.
     */
    fun onTurnEnded(spoke: Boolean, enabled: Boolean): Effect? {
        if (!enabled || !spoke) {
            state = ControllerState.IDLE
            return null
        }
        state = ControllerState.OPEN
        deadlineMs = nowMs() + windowMs
        return Effect.OpenWindow
    }

    /**
     * VAD onset observed. Only meaningful while OPEN — outside the window
     * the VAD is idle (no collector running) so stray true values are ignored.
     */
    fun onVadActive(): Effect? =
        if (state == ControllerState.OPEN) {
            state = ControllerState.IDLE
            Effect.StartFollowUpTurn
        } else null

    /** Wake word accepted (any state) — the normal path supersedes the window. */
    fun onWakeWord() {
        state = ControllerState.IDLE
    }

    /** Explicit cancellation (cancelAll / mute / error). */
    fun onCancelled() {
        state = ControllerState.IDLE
    }

    /**
     * Time tick — call regularly while OPEN (the SessionManager drives it
     * from the window collector loop). Emits [Effect.ExpireWindow] exactly
     * once when the deadline passes.
     */
    fun transition(): Effect? {
        if (state == ControllerState.OPEN && nowMs() >= deadlineMs) {
            state = ControllerState.IDLE
            return Effect.ExpireWindow
        }
        return null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 5_000L
        const val MIN_WINDOW_MS = 2_000L
        const val MAX_WINDOW_MS = 12_000L
    }
}
