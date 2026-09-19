package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structural guard for the settings screen after the U1 decomposition: the layout
 * was split from one 1100-line file into a host plus nine `settings_card_*.xml`
 * includes. `SettingsActivity` has no other automated coverage (no JVM and no
 * instrumentation test exercises it), and `R.id.*` constants are generated from
 * *all* layouts, so a renamed or dropped id keeps compiling and only crashes at
 * runtime. This test ties the three pieces together statically:
 *
 *  1. every `R.id.X` referenced from `SettingsActivity.kt` is declared by the host
 *     or one of its includes;
 *  2. no id is declared twice across the split files (duplicate ids silently
 *     override and leave findViewById returning the wrong view);
 *  3. every `settings_card_*.xml` on disk is referenced exactly once, and every
 *     `<include>` carries explicit layout_width/layout_height (the include
 *     override rule is subtle enough that this screen should not rely on it).
 */
class SettingsLayoutTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun resolve(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        candidates.firstOrNull { it.isFile }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            val current = walk
            if (current != null) {
                val candidate = File(current, "app/$relative")
                if (candidate.isFile) return candidate
                walk = current.parentFile
            }
        }
        return candidates[0]
    }

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
        assertTrue("layout dir not found: $relative (wd=${File(".").absoluteFile})", fallback.isDirectory)
        return fallback
    }

    private fun documents(path: File): org.w3c.dom.Document {
        assertTrue("layout not found: ${path.path}", path.isFile)
        // Namespace-aware, otherwise getNamedItemNS(androidNs, …) finds nothing.
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        return factory.newDocumentBuilder().parse(path)
    }

    /** Ids declared (`@+id/…`) by every element in [doc]. */
    private fun declaredIds(doc: org.w3c.dom.Document): Set<String> {
        val elements = doc.getElementsByTagName("*")
        val out = mutableSetOf<String>()
        for (i in 0 until elements.length) {
            val raw = elements.item(i).attributes?.getNamedItemNS(androidNs, "id")?.nodeValue
                ?: continue
            out += raw.removePrefix("@+id/").removePrefix("@id/")
        }
        return out
    }

    /** Host + every transitively included layout file. */
    private fun layoutClosure(host: File, dir: File): List<File> {
        val seen = linkedSetOf<File>()
        fun visit(file: File) {
            if (!seen.add(file)) return
            val doc = documents(file)
            val includes = doc.getElementsByTagName("include")
            for (i in 0 until includes.length) {
                val ref = includes.item(i).attributes?.getNamedItemNS(androidNs, "layout")?.nodeValue
                    ?: continue
                val name = ref.removePrefix("@layout/")
                visit(File(dir, "$name.xml"))
            }
        }
        visit(host)
        return seen.toList()
    }

    private fun settingsActivitySource(): String {
        return resolve("src/main/java/com/jarvis/assistant/SettingsActivity.kt").readText()
    }

    @Test
    fun `every id referenced by SettingsActivity is declared in the split layouts`() {
        val dir = resolveDir("src/main/res/layout")
        val files = layoutClosure(File(dir, "activity_settings.xml"), dir)
        val declared = files.flatMap { declaredIds(documents(it)) }.toSet()

        val source = settingsActivitySource()
        val regex = Regex("""\bR\.id\.([A-Za-z0-9_]+)""")
        val referenced = regex.findAll(source).map { it.groupValues[1] }.toSet()

        assertTrue("no R.id references parsed out of SettingsActivity", referenced.isNotEmpty())
        val missing = (referenced - declared).sorted()
        assertEquals(
            "SettingsActivity references ids no layout declares: $missing",
            emptyList<String>(),
            missing,
        )
    }

    @Test
    fun `no layout id is declared twice across the split files`() {
        val dir = resolveDir("src/main/res/layout")
        val files = layoutClosure(File(dir, "activity_settings.xml"), dir)

        val owners = mutableMapOf<String, String>()
        val duplicates = mutableListOf<String>()
        for (file in files) {
            for (id in declaredIds(documents(file))) {
                val previous = owners.put(id, file.name)
                if (previous != null) duplicates += "$id ($previous, ${file.name})"
            }
        }
        assertEquals("duplicate layout ids: $duplicates", emptyList<String>(), duplicates.sorted())
    }

    @Test
    fun `every settings card is included exactly once with explicit sizing`() {
        val dir = resolveDir("src/main/res/layout")
        val host = documents(File(dir, "activity_settings.xml"))
        val includes = host.getElementsByTagName("include")

        val refs = mutableListOf<String>()
        val unsized = mutableListOf<String>()
        for (i in 0 until includes.length) {
            val attrs = includes.item(i).attributes
            val ref = attrs.getNamedItemNS(androidNs, "layout")?.nodeValue ?: continue
            val name = ref.removePrefix("@layout/")
            refs += name
            val width = attrs.getNamedItemNS(androidNs, "layout_width")?.nodeValue
            val height = attrs.getNamedItemNS(androidNs, "layout_height")?.nodeValue
            if (width.isNullOrBlank() || height.isNullOrBlank()) unsized += name
        }

        assertEquals("an include is referenced more than once: $refs", refs.size, refs.toSet().size)
        assertEquals("includes missing explicit layout_width/height: $unsized", emptyList<String>(), unsized)

        val onDisk = dir.listFiles()
            ?.filter { it.name.startsWith("settings_card_") && it.name.endsWith(".xml") }
            ?.map { it.name.removeSuffix(".xml") }
            ?.sorted()
            ?: emptyList()

        assertEquals("settings_card_* files on disk", refs.sorted(), onDisk)
    }
}
