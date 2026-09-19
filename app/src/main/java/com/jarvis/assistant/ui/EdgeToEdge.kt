package com.jarvis.assistant.ui

import android.app.Activity
import android.graphics.Color
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.jarvis.assistant.R

/**
 * Single edge-to-edge entry point (REMEDIATION_PLAN Phase 8).
 *
 * The platform stops honouring the edge-to-edge opt-out at target 36, so the
 * screens are moved onto the edge-to-edge model deliberately instead of
 * arriving there by enforcement: [enable] owns the window flags, [pad] owns
 * the content padding, and every activity calls both. The composition math is
 * the pure [EdgeInsetsPolicy]; this file is only the Android glue.
 *
 * Visual note: the bar colours are set transparent but the content behind
 * them is the root's own background. `jarvis_surface` and
 * `jarvis_background` are the same colour in both day and night, so the
 * status-bar area is unchanged from the pre-edge-to-edge look — the icons and
 * the light/dark appearance keep coming from `jarvis_light_bars`.
 */
object EdgeToEdge {

    /**
     * Opts the activity's window into drawing behind the system bars. Call
     * BEFORE `setContentView`: a later switch forces a full relayout, and the
     * first pass would already have fitted the content to the bars.
     */
    fun enable(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        setBarColorsTransparent(activity)

        val lightBars = activity.resources.getBoolean(R.bool.jarvis_light_bars)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = lightBars
            isAppearanceLightNavigationBars = lightBars
        }
    }

    /**
     * The dynamic bar colours are deprecated in favour of the enforced
     * edge-to-edge flags, but this project still supports API 29, where they
     * are the only way to make the bars transparent. The content behind them
     * is the root's own background, which equals `jarvis_surface` in both
     * themes, so the status bar looks exactly as it did before.
     */
    @Suppress("DEPRECATION")
    private fun setBarColorsTransparent(activity: Activity) {
        activity.window.statusBarColor = Color.TRANSPARENT
        activity.window.navigationBarColor = Color.TRANSPARENT
    }

    /**
     * Pads [root] by the system bars, the display cutout and — only when
     * [includeIme] — the keyboard, on top of the padding the layout already
     * declares. The base padding is captured once, so a re-dispatch (rotation,
     * keyboard show/hide, a live bar-height change) recomposes idempotently
     * instead of accumulating.
     *
     * [includeIme] is for screens with text fields: `adjustResize` is inert
     * once the window draws edge-to-edge, so the form reserves the keyboard
     * itself.
     */
    fun pad(root: View, includeIme: Boolean = false) {
        val base = Sides(
            left = root.paddingLeft,
            top = root.paddingTop,
            right = root.paddingRight,
            bottom = root.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = windowInsets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val padded = EdgeInsetsPolicy.rootPadding(
                base = base,
                systemBars = Sides(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom),
                cutout = Sides(cutout.left, cutout.top, cutout.right, cutout.bottom),
                imeBottom = ime.bottom,
                includeIme = includeIme,
            )
            view.setPadding(padded.left, padded.top, padded.right, padded.bottom)
            windowInsets
        }
        ViewCompat.requestApplyInsets(root)
    }
}
