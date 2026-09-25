package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Static guard for the edge-to-edge migration (REMEDIATION_PLAN Phase 8).
 *
 * At target 36 the platform stops honouring the edge-to-edge opt-out, so a
 * screen that never calls [com.jarvis.assistant.ui.EdgeToEdge.enable] loses its
 * insets, and one that calls it without the matching
 * [com.jarvis.assistant.ui.EdgeToEdge.pad] draws its content under the status
 * bar and the keyboard. Neither failure is visible to the JVM suite: the
 * activities have no other coverage (no Robolectric in the catalog, and
 * `androidTest` is nightly-only), and `R.id.*` is generated from every layout,
 * so a renamed root id keeps compiling and only breaks on a device.
 *
 * This test therefore checks the three pieces that must stay in lockstep, per
 * activity that calls `setContentView`:
 *  1. `EdgeToEdge.enable(this)` runs BEFORE `setContentView` (the order matters:
 *     switching after the first layout pass would have already fitted the
 *     content to the bars);
 *  2. `EdgeToEdge.pad(findViewById(R.id.X))` runs AFTER it;
 *  3. `X` is declared on that layout's ROOT element, so the padding lands on
 *     the view the insets actually apply to.
 */
class EdgeToEdgeWiringTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun resolveDir(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        candidates.firstOrNull { it.isDirectory }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            val current = walk
            if (current != null) {
                val candidate = File(current, "app/$relative")
                if (candidate.isDirectory) return candidate
                walk = current.parentFile
            }
        }
        val fallback = candidates[0]
        assertTrue("directory not found: $relative (wd=${File(".").absoluteFile})", fallback.isDirectory)
        return fallback
    }

    private fun sourceFiles(): List<File> =
        resolveDir("src/main/java/com/jarvis/assistant")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun rootIdOf(layout: String): String {
        val file = File(resolveDir("src/main/res/layout"), "$layout.xml")
        assertTrue("layout missing: ${file.path}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val root = factory.newDocumentBuilder().parse(file).documentElement
        return root.getAttributeNS(androidNs, "id")
    }

    @Test
    fun `every activity opts into edge to edge before setting its content view`() {
        val contentView = Regex("""setContentView\(R\.layout\.(\w+)\)""")
        val padCall = Regex("""EdgeToEdge\.pad\(findViewById\(R\.id\.(\w+)\)""")

        val screens = sourceFiles().mapNotNull { file ->
            val text = file.readText()
            val match = contentView.find(text) ?: return@mapNotNull null
            file.name to (text to match)
        }

        assertEquals("expected the seven edge-to-edge screens", 7, screens.size)

        for ((name, pair) in screens) {
            val (text, match) = pair
            val layout = match.groupValues[1]

            val enableAt = text.indexOf("EdgeToEdge.enable(this)")
            assertTrue("$name does not call EdgeToEdge.enable(this)", enableAt >= 0)
            assertTrue("$name must opt in BEFORE setContentView($layout)", enableAt < match.range.first)

            val pad = padCall.find(text, match.range.last)
            assertTrue("$name does not call EdgeToEdge.pad(...) after setContentView", pad != null)
            val rootId = requireNotNull(pad).groupValues[1]
            assertEquals(
                "$name pads R.id.$rootId but that id is not the root of $layout.xml",
                "@+id/$rootId",
                rootIdOf(layout),
            )
        }
    }

    @Test
    fun `only the settings screen reserves the keyboard for the ime`() {
        val imeActivities = sourceFiles()
            .filter { it.readText().contains("EdgeToEdge.pad(") }
            .filter { it.readText().contains("includeIme = true") }
            .map { it.name }
            .sorted()

        // Both settings hosts keep the IME reservation: the LIST host (the old
        // single screen, now a category picker) and the DETAIL host, where the
        // text fields actually live. MainActivity has none.
        assertEquals(listOf("SettingsActivity.kt", "SettingsDetailActivity.kt"), imeActivities)
    }
}
