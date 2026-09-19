package com.jarvis.assistant

import com.jarvis.assistant.ui.EdgeInsetsPolicy
import com.jarvis.assistant.ui.Sides
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composition half of edge-to-edge (REMEDIATION_PLAN Phase 8). Activities
 * have no JVM or instrumentation coverage in this project, so the arithmetic
 * that decides how much padding every screen root gets is kept pure and pinned
 * here; [EdgeToEdge] is only the view/window glue around it.
 *
 * The invariants that matter in production:
 *  - the layout's own padding (for example the ringing screen's 32dp bottom)
 *    survives, because edge-to-edge ADDS to it;
 *  - a display cutout never produces LESS inset than the status bar on the
 *    same side;
 *  - the keyboard only moves the bottom, and only when the caller asked.
 */
class EdgeInsetsPolicyTest {

    private fun sides(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        Sides(left = left, top = top, right = right, bottom = bottom)

    @Test
    fun `no insets leaves the base padding untouched`() {
        val base = sides(top = 24, bottom = 32)
        val result = EdgeInsetsPolicy.rootPadding(
            base = base,
            systemBars = sides(),
            cutout = sides(),
            imeBottom = 0,
            includeIme = false,
        )
        assertEquals(base, result)
    }

    @Test
    fun `system bars add to the base padding`() {
        val result = EdgeInsetsPolicy.rootPadding(
            base = sides(top = 24, bottom = 32),
            systemBars = sides(left = 12, top = 24, right = 12, bottom = 48),
            cutout = sides(),
            imeBottom = 0,
            includeIme = false,
        )
        assertEquals(sides(left = 12, top = 48, right = 12, bottom = 80), result)
    }

    @Test
    fun `cutout wins only when it is larger on that side`() {
        val result = EdgeInsetsPolicy.rootPadding(
            base = sides(),
            systemBars = sides(top = 24, bottom = 48),
            cutout = sides(top = 80, bottom = 10),
            imeBottom = 0,
            includeIme = false,
        )
        assertEquals(sides(top = 80, bottom = 48), result)
    }

    @Test
    fun `keyboard is ignored when the screen did not opt in`() {
        val result = EdgeInsetsPolicy.rootPadding(
            base = sides(bottom = 16),
            systemBars = sides(bottom = 48),
            cutout = sides(),
            imeBottom = 900,
            includeIme = false,
        )
        assertEquals(sides(bottom = 64), result)
    }

    @Test
    fun `keyboard joins the bottom when the screen opted in`() {
        val result = EdgeInsetsPolicy.rootPadding(
            base = sides(bottom = 16),
            systemBars = sides(top = 24, bottom = 48),
            cutout = sides(),
            imeBottom = 900,
            includeIme = true,
        )
        assertEquals(sides(top = 24, bottom = 916), result)
    }

    @Test
    fun `a hidden keyboard adds nothing even when opted in`() {
        val result = EdgeInsetsPolicy.rootPadding(
            base = sides(bottom = 16),
            systemBars = sides(bottom = 48),
            cutout = sides(),
            imeBottom = 0,
            includeIme = true,
        )
        assertEquals(sides(bottom = 64), result)
    }

    @Test
    fun `keyboard never shrinks a larger system bar inset`() {
        val result = EdgeInsetsPolicy.rootPadding(
            base = sides(),
            systemBars = sides(bottom = 128),
            cutout = sides(),
            imeBottom = 60,
            includeIme = true,
        )
        assertEquals(sides(bottom = 128), result)
    }
}
