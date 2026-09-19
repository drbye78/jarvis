package com.jarvis.assistant

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.pow

/**
 * WCAG contrast guard for the status palette (findings U3 / N8). Parses the real
 * `colors.xml` files — no Robolectric, no Android framework — and checks every
 * (foreground, background) pairing that ships on screen, so a future palette
 * tweak that quietly drops below the threshold fails here instead of on a user's
 * device.
 *
 * Thresholds: normal text >= 4.5:1, large non-text graphics >= 3.0:1.
 */
class StatusContrastTest {

    /** A foreground color drawn over a background color in a real screen. */
    private data class ColorPair(
        val foreground: String,
        val background: String,
        val usage: String,
    )

    private fun colorsFile(localeDir: String): File {
        // Unit tests run with the module dir as the working directory; walk up
        // defensively so this also works from the repo root.
        val candidates = listOf(
            File("src/main/res/$localeDir/colors.xml"),
            File("app/src/main/res/$localeDir/colors.xml"),
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            if (walk != null) {
                val candidate = File(walk, "app/src/main/res/$localeDir/colors.xml")
                if (candidate.isFile) return candidate
                walk = walk!!.parentFile
            }
        }
        return candidates[0]
    }

    private fun colors(localeDir: String): Map<String, String> {
        val file = colorsFile(localeDir)
        assertTrue(
            "colors.xml not found for $localeDir (wd=${File(".").absoluteFile})",
            file.exists(),
        )
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("color")
        val out = mutableMapOf<String, String>()
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            out[node.attributes.getNamedItem("name").nodeValue] = node.textContent.trim()
        }
        return out
    }

    private fun assertPairs(localeDir: String, pairs: List<ColorPair>, minRatio: Double) {
        val palette = colors(localeDir)
        pairs.forEach { pair ->
            val foreground = palette[pair.foreground]
            assertNotNull("missing '${pair.foreground}' in $localeDir/colors.xml", foreground)
            val background = palette[pair.background]
            assertNotNull("missing '${pair.background}' in $localeDir/colors.xml", background)
            val ratio = contrastRatio(foreground!!, background!!)
            assertTrue(
                "$localeDir: ${pair.foreground} on ${pair.background} (${pair.usage}) " +
                    "is $ratio:1, needs >= $minRatio:1",
                ratio >= minRatio,
            )
        }
    }

    private fun contrastRatio(foreground: String, background: String): Double {
        val a = relativeLuminance(foreground)
        val b = relativeLuminance(background)
        val hi = maxOf(a, b)
        val lo = minOf(a, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun relativeLuminance(raw: String): Double {
        val argb = parseArgb(raw)
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    private fun channel(component: Int): Double {
        val c = component / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** Parses `#RRGGBB` or `#AARRGGBB`; the alpha channel is ignored for contrast. */
    private fun parseArgb(raw: String): Int {
        val hex = raw.trim().removePrefix("#")
        require(hex.length == 6 || hex.length == 8) { "unsupported color literal '$raw'" }
        val value = hex.toLong(16)
        return if (hex.length == 6) (0xFF shl 24) or value.toInt() else value.toInt()
    }

    @Test
    fun `day text pairs meet WCAG AA on their real backgrounds`() =
        assertPairs("values", TEXT_PAIRS, MIN_TEXT_RATIO)

    @Test
    fun `night text pairs meet WCAG AA on their real backgrounds`() =
        assertPairs("values-night", TEXT_PAIRS, MIN_TEXT_RATIO)

    @Test
    fun `day large-graphic pairs meet the 3 to 1 threshold`() =
        assertPairs("values", GRAPHIC_PAIRS, MIN_GRAPHIC_RATIO)

    @Test
    fun `night large-graphic pairs meet the 3 to 1 threshold`() =
        assertPairs("values-night", GRAPHIC_PAIRS, MIN_GRAPHIC_RATIO)

    companion object {
        private const val MIN_TEXT_RATIO = 4.5
        private const val MIN_GRAPHIC_RATIO = 3.0

        /**
         * Foreground colors rendered as normal text: the Settings status rows
         * draw `listening`/`thinking` on `jarvis_surface`, and the status pill
         * styles its label with `colorOnSurfaceVariant` over the
         * `bg_status_pill` `colorPrimaryContainer` fill.
         */
        private val TEXT_PAIRS = listOf(
            ColorPair("jarvis_status_listening", "jarvis_surface", "Settings OK status text"),
            ColorPair("jarvis_status_thinking", "jarvis_surface", "Settings unverifiable status text"),
            ColorPair("jarvis_on_surface_variant", "jarvis_primary_container", "status pill text"),
        )

        /**
         * Large non-text graphics (>= 3.0:1). The orb colors are drawn on the
         * window background; the ringing screen tints its alarm icon
         * `jarvis_status_thinking` inside a `colorSurfaceVariant` chip.
         */
        private val GRAPHIC_PAIRS = listOf(
            ColorPair("jarvis_status_idle", "jarvis_background", "orb idle ring"),
            ColorPair("jarvis_status_listening", "jarvis_background", "orb listening core"),
            ColorPair("jarvis_status_thinking", "jarvis_background", "orb thinking arcs"),
            ColorPair("jarvis_status_speaking", "jarvis_background", "orb speaking core"),
            ColorPair("jarvis_status_thinking", "jarvis_surface_variant", "ringing alarm icon chip"),
        )
    }
}
