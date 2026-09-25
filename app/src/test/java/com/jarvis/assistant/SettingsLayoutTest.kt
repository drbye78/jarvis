package com.jarvis.assistant

import com.jarvis.assistant.settings.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structural guard for the redesigned settings screens (FLIP lane).
 *
 * The old test assumed ONE host holding ALL cards; the redesign splits the
 * surface into a category LIST host ([SettingsActivity] + `activity_settings.xml`),
 * one reusable DETAIL host ([SettingsDetailActivity] + `activity_settings_detail.xml`)
 * and eight `screen_settings_*.xml` screens driven by eight controllers. `R.id.*`
 * is generated from ALL layouts, so a typo'd or renamed id keeps compiling and
 * only crashes at runtime — these checks tie registry, layouts, includes and
 * controllers together statically:
 *
 *  1. every [SettingsCategory] has a real, non-empty `screen_settings_<id>.xml`
 *     (the id→filename convention is fixed by the enum's own KDoc);
 *  2. each screen's controller references ONLY ids that screen declares, or that
 *     a shared include (`settings_disclosure.xml`, `settings_apply_banner.xml`)
 *     declares;
 *  3. no id is declared by two DIFFERENT screen files (or the hosts) — a
 *     duplicate silently overrides and `findViewById` returns the wrong view;
 *     shared includes are visited once, never double-counted;
 *  4. every `<include>` in a screen or host carries explicit layout_width/height;
 *  5. the two hosts declare every id their Activity references (the list host
 *     also owns `item_settings_category.xml`, inflated by its adapter);
 *  6. no orphan `screen_settings_*.xml` exists outside the registry.
 */
class SettingsLayoutTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Layouts shared by several screens; their ids are always in scope. */
    private val sharedIncludes = listOf("settings_disclosure.xml", "settings_apply_banner.xml")

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

    /** Every `R.id.X` referenced from a Kotlin source file. */
    private fun referencedIds(source: File): Set<String> {
        val text = source.readText()
        return Regex("""\bR\.id\.([A-Za-z0-9_]+)""").findAll(text).map { it.groupValues[1] }.toSet()
    }

    /** Host + every transitively included layout file. */
    private fun layoutClosure(host: File, dir: File): List<File> {
        val seen = linkedSetOf<File>()
        fun visit(file: File) {
            if (!seen.add(file)) return
            val doc = documents(file)
            val includes = doc.getElementsByTagName("include")
            for (i in 0 until includes.length) {
                val ref = includes.item(i).attributes?.getNamedItem("layout")?.nodeValue
                    ?: continue
                val name = ref.removePrefix("@layout/")
                visit(File(dir, "$name.xml"))
            }
        }
        visit(host)
        return seen.toList()
    }

    private fun screenFile(dir: File, category: SettingsCategory): File =
        File(dir, "screen_settings_${category.id}.xml")

    /** The controller source that drives [category] (explicit: a new arm must be added here). */
    private fun controllerFileForCategory(): Map<SettingsCategory, String> = mapOf(
        SettingsCategory.BRAIN to "BrainSettingsController.kt",
        SettingsCategory.SPEECH to "SpeechSettingsController.kt",
        SettingsCategory.LISTENING to "ListeningSettingsController.kt",
        SettingsCategory.WEATHER_MAPS to "WeatherMapsSettingsController.kt",
        SettingsCategory.MEMORY to "MemorySettingsController.kt",
        SettingsCategory.PROACTIVITY to "ProactivitySettingsController.kt",
        SettingsCategory.MUSIC to "MusicSettingsController.kt",
        SettingsCategory.ACCOUNTS to "AccountsSettingsController.kt",
    )

    @Test
    fun `every category has a real non-empty screen layout`() {
        val dir = resolveDir("src/main/res/layout")
        SettingsCategory.entries.forEach { category ->
            val file = screenFile(dir, category)
            assertTrue("no screen layout for ${category.name}: expected ${file.name}", file.isFile)
            assertTrue("screen layout ${file.name} is empty", file.length() > 0)
        }
    }

    @Test
    fun `each screen controller references only ids its screen or a shared include declares`() {
        val dir = resolveDir("src/main/res/layout")
        val shared = sharedIncludes.flatMap { declaredIds(documents(File(dir, it))) }.toSet()
        val controllers = controllerFileForCategory()
        assertEquals(
            "controller mapping must cover every category",
            SettingsCategory.entries.toSet(),
            controllers.keys,
        )

        controllers.forEach { (category, controllerName) ->
            val declared = declaredIds(documents(screenFile(dir, category))) + shared
            val referenced = referencedIds(
                resolve("src/main/java/com/jarvis/assistant/settings/controller/$controllerName"),
            )
            assertTrue("no R.id references parsed out of $controllerName", referenced.isNotEmpty())
            val missing = (referenced - declared).sorted()
            assertEquals(
                "${category.name} controller references ids no layout declares: $missing",
                emptyList<String>(),
                missing,
            )
        }
    }

    @Test
    fun `no id is declared twice across screens and hosts`() {
        val dir = resolveDir("src/main/res/layout")
        val files = SettingsCategory.entries.map { screenFile(dir, it) } +
            listOf(File(dir, "activity_settings.xml"), File(dir, "activity_settings_detail.xml"))

        val owners = mutableMapOf<String, String>()
        val duplicates = mutableListOf<String>()
        for (file in files) {
            // Own ids only: shared includes are intentionally shared and are not
            // counted here (they would otherwise look like duplicates).
            for (id in declaredIds(documents(file))) {
                val previous = owners.put(id, file.name)
                if (previous != null) duplicates += "$id ($previous, ${file.name})"
            }
        }
        assertEquals("duplicate layout ids: $duplicates", emptyList<String>(), duplicates.sorted())
    }

    @Test
    fun `every include in a screen or host is explicitly sized`() {
        val dir = resolveDir("src/main/res/layout")
        val files = SettingsCategory.entries.map { screenFile(dir, it) } +
            listOf(File(dir, "activity_settings.xml"), File(dir, "activity_settings_detail.xml"))

        val unsized = mutableListOf<String>()
        for (file in files) {
            val includes = documents(file).getElementsByTagName("include")
            for (i in 0 until includes.length) {
                val attrs = includes.item(i).attributes
                val ref = attrs.getNamedItem("layout")?.nodeValue ?: continue
                // Sizing overrides ARE namespaced (android:layout_width/height).
                val width = attrs.getNamedItemNS(androidNs, "layout_width")?.nodeValue
                val height = attrs.getNamedItemNS(androidNs, "layout_height")?.nodeValue
                if (width.isNullOrBlank() || height.isNullOrBlank()) {
                    unsized += "${file.name}:$ref"
                }
            }
        }
        assertEquals("includes missing explicit layout_width/height: $unsized", emptyList<String>(), unsized)
    }

    @Test
    fun `the list and detail hosts declare every id their activity references`() {
        val dir = resolveDir("src/main/res/layout")

        // List host. The adapter inflates item_settings_category.xml, so its ids
        // are in scope for SettingsActivity too (not an <include>).
        val listDeclared = layoutClosure(File(dir, "activity_settings.xml"), dir)
            .flatMap { declaredIds(documents(it)) }
            .toSet() + declaredIds(documents(File(dir, "item_settings_category.xml")))
        val listMissing = (
            referencedIds(resolve("src/main/java/com/jarvis/assistant/SettingsActivity.kt")) - listDeclared
            ).sorted()
        assertEquals(
            "SettingsActivity references ids no list layout declares: $listMissing",
            emptyList<String>(),
            listMissing,
        )

        // Detail host: it inflates EXACTLY ONE screen at runtime, so any
        // screen's ids are legitimately in scope for its Activity (the host's
        // fieldLayouts() looks up the screen's TextInputLayouts to attach
        // validation errors). Allow the host closure + the union of all screens.
        val screenIds = SettingsCategory.entries
            .flatMap { declaredIds(documents(screenFile(dir, it))) }
            .toSet()
        val detailDeclared = layoutClosure(File(dir, "activity_settings_detail.xml"), dir)
            .flatMap { declaredIds(documents(it)) }
            .toSet() + screenIds
        val detailMissing = (
            referencedIds(resolve("src/main/java/com/jarvis/assistant/SettingsDetailActivity.kt")) - detailDeclared
            ).sorted()
        assertEquals(
            "SettingsDetailActivity references ids no detail layout declares: $detailMissing",
            emptyList<String>(),
            detailMissing,
        )
    }

    @Test
    fun `every screen_settings layout on disk belongs to a category`() {
        val dir = resolveDir("src/main/res/layout")
        val onDisk = dir.listFiles()
            ?.filter { it.name.startsWith("screen_settings_") && it.name.endsWith(".xml") }
            ?.map { it.name.removePrefix("screen_settings_").removeSuffix(".xml") }
            ?.sorted()
            ?: emptyList()
        val registered = SettingsCategory.entries.map { it.id }.sorted()
        assertEquals("orphan screen_settings_*.xml files", registered, onDisk)
    }
}
