package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Single source of truth for "may this move?".
 *
 * Reduced motion must be honored as the system reports it, from two places:
 * `ValueAnimator.areAnimatorsEnabled()` (the framework's own switch) and
 * [Settings.Global.ANIMATOR_DURATION_SCALE] (the developer-options /
 * accessibility "Remove animations" scale). A zero scale means "no
 * animation", never "instant animation" — the distinction matters because a
 * zero-duration animator still posts frames and still reads as movement to
 * someone who asked for none.
 *
 * The decision itself is the pure [shouldAnimate] function so the policy is
 * JVM-testable without Robolectric; everything below it is thin Android glue
 * that supplies live values and keeps a synchronous cache for the draw and
 * scroll paths (a binder call per frame would be a real cost).
 *
 * Lifecycle: [start] registers one [ContentObserver] and [stop] removes it.
 * Callers are activity lifecycle owners (see `MainActivity.onStart/onStop`).
 * Views that need to react to a live flip register with [addListener] and
 * MUST unregister in `onDetachedFromWindow` so a destroyed view cannot be
 * retained by the observer.
 */
object Motion {

    /**
     * Transcript row insert. Applied to the list's item-insert animation
     * duration so a new exchange lands in the plan's one approved motion.
     */
    const val TRANSCRIPT_INSERT_MS = 180L

    /** Status-pill label crossfade when the assistant changes state. */
    const val PILL_CROSSFADE_MS = 180L

    /**
     * Ringing-screen icon pulse. Contract-only today: the ringing screen is
     * currently static, so nothing consumes this yet. It exists so that if a
     * pulse is ever added it reads from here instead of inventing a duration
     * and skipping the reduced-motion gate.
     */
    const val RINGING_PULSE_MS = 1_200L

    /**
     * Window enter/exit. Contract-only today: the app defines no themed
     * window animation (no `res/anim/`, no `windowAnimationStyle`), so there
     * is nothing to gate. Do not fabricate an animation to consume it.
     */
    const val WINDOW_ENTER_MS = 220L

    /**
     * Pure reduced-motion decision. Motion runs only when the framework has
     * animators enabled AND the system animation scale is above zero.
     */
    fun shouldAnimate(animatorsEnabled: Boolean, durationScale: Float): Boolean =
        animatorsEnabled && durationScale > 0f

    @Volatile
    private var enabled = frameworkAnimatorsEnabled()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private var resolver: ContentResolver? = null

    private var observer: ContentObserver? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Synchronous reduced-motion read for draw/scroll paths. Safe before
     * [start]: it falls back to the framework switch until the live scale has
     * been read once.
     */
    fun animationsEnabled(): Boolean = enabled

    /** Notified on the main thread whenever the effective setting changes. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Registers the live ANIMATOR_DURATION_SCALE observer and refreshes the
     * cached value. Idempotent — a second call while running is a no-op.
     */
    fun start(context: Context) {
        if (observer != null) return
        val contentResolver = context.applicationContext.contentResolver
        val contentObserver = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                refresh(contentResolver)
            }
        }
        resolver = contentResolver
        observer = contentObserver
        contentResolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            contentObserver,
        )
        refresh(contentResolver)
    }

    /** Removes the observer. Safe to call when never started. */
    fun stop() {
        val contentResolver = resolver
        val contentObserver = observer
        if (contentResolver != null && contentObserver != null) {
            runCatching { contentResolver.unregisterContentObserver(contentObserver) }
        }
        resolver = null
        observer = null
    }

    private fun refresh(contentResolver: ContentResolver) {
        enabled = shouldAnimate(
            frameworkAnimatorsEnabled(),
            durationScale(contentResolver),
        )
        // CopyOnWriteArrayList: a listener may add/remove listeners while
        // being notified, which must not disturb this iteration.
        for (listener in listeners) listener()
    }

    /**
     * The framework switch, read defensively: the JVM unit-test stub of
     * `android.jar` throws when a framework static is invoked, and the pure
     * [shouldAnimate] policy must stay reachable from plain unit tests. On a
     * real device this returns the true value; if the read ever fails we
     * prefer "animate" so a broken read cannot silently freeze the UI.
     */
    private fun frameworkAnimatorsEnabled(): Boolean =
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)

    private fun durationScale(contentResolver: ContentResolver): Float =
        runCatching {
            Settings.Global.getFloat(
                contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
            )
        }.getOrDefault(1f)
}
