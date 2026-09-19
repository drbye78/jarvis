package com.jarvis.assistant.ui

/**
 * Edge-to-edge inset policy (REMEDIATION_PLAN Phase 8).
 *
 * At target 36 the platform stops honouring the edge-to-edge opt-out, so
 * every screen root must pad itself by the system bars it now draws behind.
 * This is the pure half of [EdgeToEdge]: the four numbers come in as plain
 * values and the resulting padding goes out as plain values, so the
 * composition rules are JVM-testable without a device (this project has no
 * Robolectric).
 *
 * Rules:
 *  - the layout's OWN padding is preserved: header/body padding in the XML is
 *    deliberate, so edge-to-edge ADDS to it and never replaces it;
 *  - each side takes the largest inset reaching it, because a display cutout
 *    and the status bar both indent the top and only the larger one lies
 *    below the visible obstruction;
 *  - the keyboard is folded into the bottom only when the caller asked for it
 *    ([includeIme]): once the window draws edge-to-edge, `adjustResize` no
 *    longer resizes anything, so a scrollable form must reserve the keyboard
 *    itself or the focused field stays hidden behind it.
 */
data class Sides(
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
)

object EdgeInsetsPolicy {

    /**
     * Padding for an edge-to-edge screen root: [base] (the root's own XML
     * padding) plus, per side, the largest inset overlapping that side.
     * [imeBottom] joins the bottom only when [includeIme] is set — a hidden
     * keyboard reports a zero inset, so the flag is what keeps the behaviour
     * explicit rather than incidental.
     */
    fun rootPadding(
        base: Sides,
        systemBars: Sides,
        cutout: Sides,
        imeBottom: Int,
        includeIme: Boolean,
    ): Sides = Sides(
        left = base.left + maxOf(systemBars.left, cutout.left),
        top = base.top + maxOf(systemBars.top, cutout.top),
        right = base.right + maxOf(systemBars.right, cutout.right),
        bottom = base.bottom + maxOf(
            systemBars.bottom,
            cutout.bottom,
            if (includeIme) imeBottom else 0,
        ),
    )
}
