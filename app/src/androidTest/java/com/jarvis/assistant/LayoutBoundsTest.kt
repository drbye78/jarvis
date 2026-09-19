package com.jarvis.assistant

import android.app.Activity
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jarvis.assistant.util.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-level guard for the "reading column" invariant on BOTH the home screen
 * ([MainActivity]) and [SettingsActivity].
 *
 * The regression class this pins: `android:maxWidth` is TextView-only and is
 * silently ignored on a `LinearLayout`, and a fixed-dp `layout_width` on a
 * `layout_gravity="center_horizontal"` child of a `FrameLayout`/`ScrollView` is
 * NOT clamped — it centres at `left = (parentWidth - childWidth) / 2`, which goes
 * negative and spills the child off both edges. Production now caps the column at
 * runtime in `onCreate`, so this test measures ABSOLUTE on-screen bounds and
 * checks [MainActivity] and [SettingsActivity] both honour it.
 *
 * This file is instrumentation-only: it never reads, asserts on or logs any
 * credential field content, and it touches no production code.
 */
@RunWith(AndroidJUnit4::class)
class LayoutBoundsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mainActivity_readingColumn_isCappedAndContained() {
        // MainActivity.onCreate redirects to OnboardingActivity (and finishes)
        // when `onboarded` is false, so force it on for the launch and restore
        // the owner's original value afterwards — never mutate device state.
        withOnboardingSuppressed {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(
                        "MainActivity finished on launch — the onboarding redirect was not defeated",
                        activity.isFinishing,
                    )
                }
                val layout = captureLayout(
                    scenario = scenario,
                    rootId = R.id.mainRoot,
                    columnId = R.id.homeColumn,
                    controlIds = listOf(R.id.settingsButton, R.id.alarmsButton, R.id.micButton),
                )
                assertReadingColumn(layout, HOME_COLUMN_MAX_WIDTH_DP, "homeColumn")
                assertControlsInsideWindow(layout, "home")
            }
        }
    }

    @Test
    fun settingsActivity_readingColumn_isCappedAndContained() {
        // SettingsActivity has no onboarding gate; it launches standalone.
        // Wide child: the provider RadioGroup (match_parent) from the first card.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            val layout = captureLayout(
                scenario = scenario,
                rootId = R.id.settingsRoot,
                columnId = R.id.settingsColumn,
                controlIds = listOf(R.id.llmProviderGroup),
            )
            assertReadingColumn(layout, SETTINGS_COLUMN_MAX_WIDTH_DP, "settingsColumn")
            assertControlsInsideWindow(layout, "settings")
        }
    }

    /**
     * Runs [block] with `AppPrefs.onboarded` forced true and restores the
     * original value in a `finally` even when the block fails.
     */
    private fun withOnboardingSuppressed(block: () -> Unit) {
        val prefs = AppPrefs(context)
        val original = prefs.onboarded
        prefs.onboarded = true
        try {
            block()
        } finally {
            prefs.onboarded = original
        }
    }

    /**
     * Waits (bounded) until [rootId] has been measured + laid out, then captures
     * the root, the reading column, the display metrics and every control in one
     * main-thread pass. Polling is required because `ActivityScenario.launch`
     * waits for the RESUMED lifecycle state, not for the first traversal.
     */
    private fun <A : Activity> captureLayout(
        scenario: ActivityScenario<A>,
        rootId: Int,
        columnId: Int,
        controlIds: List<Int>,
    ): Layout {
        awaitLaidOut(scenario, rootId)
        var root: Bounds? = null
        var column: Bounds? = null
        var windowWidthPx = 0
        var density = 0f
        val controls = LinkedHashMap<Int, Bounds>()
        scenario.onActivity { activity ->
            val metrics = activity.resources.displayMetrics
            windowWidthPx = metrics.widthPixels
            density = metrics.density
            root = boundsIn(activity, rootId)
            column = boundsIn(activity, columnId)
            controlIds.forEach { id -> controls[id] = boundsIn(activity, id) }
        }
        return Layout(
            root = requireNotNull(root),
            column = requireNotNull(column),
            windowWidthPx = windowWidthPx,
            density = density,
            controls = controls,
        )
    }

    /** Bounded wait for a real layout pass on [viewId]. */
    private fun <A : Activity> awaitLaidOut(scenario: ActivityScenario<A>, viewId: Int) {
        repeat(MAX_LAYOUT_POLLS) {
            var laidOut = false
            scenario.onActivity { activity ->
                val view = activity.findViewById<View>(viewId)
                assertNotNull("view $viewId missing from the hierarchy", view)
                laidOut = view.width > 0 && view.height > 0
            }
            if (laidOut) {
                return
            }
            Thread.sleep(LAYOUT_POLL_INTERVAL_MS)
        }
        throw AssertionError("view $viewId never reached a laid-out size")
    }

    /**
     * Absolute on-screen bounds via [View.getLocationOnScreen] plus raw size.
     * Called on the main thread from inside `scenario.onActivity`.
     */
    private fun boundsIn(activity: Activity, viewId: Int): Bounds {
        val view = activity.findViewById<View>(viewId)
        assertNotNull("view $viewId missing from the hierarchy", view)
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Bounds(location[0], location[1], view.width, view.height)
    }

    /**
     * Containment (inside the root) plus the exact cap arithmetic mirrored from
     * production: `width == min(window, capDp * density)`. This single check
     * fails BOTH when an oversized fixed width returns and when the cap becomes
     * dead code.
     */
    private fun assertReadingColumn(layout: Layout, capDp: Int, name: String) {
        val root = layout.root
        val column = layout.column
        assertTrue(
            "$name left=${column.left} spills past root left=${root.left}",
            column.left >= root.left,
        )
        assertTrue(
            "$name right=${column.right} spills past root right=${root.right}",
            column.right <= root.right,
        )
        val capPx = (capDp * layout.density).toInt()
        val expectedWidth = minOf(layout.windowWidthPx, capPx)
        assertEquals(
            "$name width=${column.width} != min(window=${layout.windowWidthPx}, cap=${capPx}px)",
            expectedWidth,
            column.width,
        )
    }

    /** Every control must stay inside the window horizontally. */
    private fun assertControlsInsideWindow(layout: Layout, name: String) {
        layout.controls.forEach { (id, bounds) ->
            assertTrue("$name control $id left=${bounds.left} is off-screen", bounds.left >= 0)
            assertTrue(
                "$name control $id right=${bounds.right} exceeds window ${layout.windowWidthPx}",
                bounds.right <= layout.windowWidthPx,
            )
        }
    }

    /** Immutable screen bounds for one view. */
    private data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    /** One fully captured screen: root, reading column, metrics and controls. */
    private class Layout(
        val root: Bounds,
        val column: Bounds,
        val windowWidthPx: Int,
        val density: Float,
        val controls: Map<Int, Bounds>,
    )

    private companion object {
        // The production caps are PRIVATE `const val`s; they are hardcoded here
        // on purpose — a test that reads the constant it validates cannot catch
        // a wrong constant. Source of truth:
        // MainActivity.HOME_COLUMN_MAX_WIDTH_DP / SettingsActivity.SETTINGS_COLUMN_MAX_WIDTH_DP.
        const val HOME_COLUMN_MAX_WIDTH_DP = 840
        const val SETTINGS_COLUMN_MAX_WIDTH_DP = 760

        /** Bounded layout polling (60 * 50 ms = 3 s worst case). */
        const val MAX_LAYOUT_POLLS = 60
        const val LAYOUT_POLL_INTERVAL_MS = 50L
    }
}
