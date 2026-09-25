package com.jarvis.assistant.settings

import android.view.View
import com.jarvis.assistant.util.AppPrefs

/**
 * A single settings detail screen (settings redesign foundation).
 *
 * A controller owns exactly one root view (its `screen_settings_*.xml`) and
 * reads/writes prefs itself; anything with a lifecycle, a permission or a
 * navigation consequence goes through [SettingsHost]. This is the seam that
 * lets each category become a small class instead of another block in the
 * 1955-line Activity.
 */
interface SettingsController {
    /** Attach to this screen's inflated root view; wire listeners here. */
    fun bind(root: View)

    /** Called when the screen becomes visible / gains user attention. */
    fun onResume()

    /** Called when the screen is going away; release listeners/collectors. */
    fun onStop()
}

/**
 * Convenience base for the common case: a controller that only needs the
 * shared callbacks, prefs and host, and has no per-lifecycle work. [onResume]
 * and [onStop] are no-ops here so a subclass overrides only what it needs;
 * [bind] stays abstract because every screen must construct itself.
 */
abstract class BaseSettingsController(
    protected val callbacks: SettingsCallbacks,
    protected val prefs: AppPrefs,
    protected val host: SettingsHost,
) : SettingsController {

    override fun onResume() {}

    override fun onStop() {}
}
