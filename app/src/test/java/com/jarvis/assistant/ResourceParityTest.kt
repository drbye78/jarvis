package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the RU/EN resource parity that previous patches established by hand:
 * every key in values/strings.xml must exist in values-en/strings.xml and
 * vice versa. A new key added to only one locale fails HERE, at the source,
 * instead of shipping a UI that lies about its language (the original
 * dead-EN-resources defect F6).
 *
 * Audit P1-B#6: the two group checks used to be hand-maintained allow-lists
 * (`phrase_asr_open_failed`, `activity_tool_set_alarm`, …). An allow-list only
 * proves that the keys it still remembers exist, so a new `phrase_*` or
 * `activity_tool_*` key was never scanned — and the list itself rotted in
 * silence. The groups are now DERIVED from the resource files by a prefix scan,
 * which covers every present and future key.
 *
 * What a derived scan cannot notice is a group going EMPTY: renaming or deleting
 * a whole vocabulary keeps both locales symmetric, so parity alone stays green.
 * [CODE_REFERENCED_PREFIXES] closes that hole for the prefixes whose words are
 * read from code. Individual keys are deliberately NOT listed — code reaches
 * them through `R.string.*`, i.e. at compile time, which no resource test can
 * outdate.
 */
class ResourceParityTest {

    private fun stringsFile(localeDir: String): File {
        // Unit tests run with the module dir as the working directory; walk
        // up defensively anyway so this also works from the repo root.
        val candidates = listOf(
            File("src/main/res/$localeDir/strings.xml"),
            File("app/src/main/res/$localeDir/strings.xml"),
        )
        candidates.firstOrNull { it.isFile }?.let { return it }
        var walk: File? = File(".").absoluteFile
        repeat(4) {
            if (walk != null) {
                val candidate = File(walk, "app/src/main/res/$localeDir/strings.xml")
                if (candidate.isFile) return candidate
                walk = walk!!.parentFile
            }
        }
        return candidates[0] // for the existence assertion in keys()
    }

    private fun keys(localeDir: String): Set<String> {
        val file = stringsFile(localeDir)
        assertTrue("strings.xml not found for $localeDir (wd=${File(".").absoluteFile})", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            out += nodes.item(i).attributes.getNamedItem("name").nodeValue
        }
        return out
    }

    /** Parsed once per test instance (JUnit builds a fresh instance per test). */
    private val ru: Set<String> by lazy { keys("values") }
    private val en: Set<String> by lazy { keys("values-en") }

    /** `activity_tool_set_alarm` -> `activity`; a key without `_` groups alone. */
    private fun prefixOf(key: String): String = key.substringBefore('_')

    private fun Set<String>.byPrefix(): Map<String, Set<String>> =
        groupBy { prefixOf(it) }.mapValues { (_, groupKeys) -> groupKeys.toSet() }

    @Test
    fun `RU and EN string keys are in parity`() {
        val missingInEn = ru - en
        val missingInRu = en - ru
        assertEquals("keys missing in values-en: $missingInEn", 0, missingInEn.size)
        assertEquals("keys missing in values: $missingInRu", 0, missingInRu.size)
    }

    /**
     * Every leading `_`-separated segment defines a group and the groups must be
     * identical in both locales. Strictly implied by the global parity assertion
     * above — kept because it names the BROKEN GROUP instead of dumping the whole
     * key set into the failure text, and because it is the check that survives the
     * deletion of the hand-maintained lists.
     */
    @Test
    fun `every string key group exists in both locales`() {
        val ruGroups = ru.byPrefix()
        val enGroups = en.byPrefix()
        val onlyInRu = ruGroups.keys - enGroups.keys
        val onlyInEn = enGroups.keys - ruGroups.keys
        assertEquals("group sets differ (only in RU: $onlyInRu, only in EN: $onlyInEn)", ruGroups.keys, enGroups.keys)
        ruGroups.forEach { (prefix, ruKeys) ->
            assertEquals("group '${prefix}_*' differs between locales", ruKeys, enGroups[prefix])
        }
    }

    /**
     * The replacement for both allow-lists, with no keys to maintain: the
     * code-referenced vocabularies must be populated in BOTH locales and stay in
     * lockstep. Adding a 12th `phrase_*` (or a new tool label) in both locales is
     * covered automatically — the old `expected = setOf(...)` skipped it.
     */
    @Test
    fun `code-referenced string groups are populated in both locales`() {
        CODE_REFERENCED_PREFIXES.forEach { prefix ->
            val marker = "${prefix}_"
            val ruKeys = ru.filter { it.startsWith(marker) }.toSet()
            val enKeys = en.filter { it.startsWith(marker) }.toSet()
            assertTrue("no '$marker' keys left in values/strings.xml", ruKeys.isNotEmpty())
            assertTrue("no '$marker' keys left in values-en/strings.xml", enKeys.isNotEmpty())
            assertEquals("group '$marker' differs between locales", ruKeys, enKeys)
        }
    }

    companion object {

        /**
         * Prefixes whose spoken/labelled vocabulary is resolved from resources:
         * `session/SpeechPhrases` (+ `AndroidSpeechPhrases`) and
         * `session/TurnActivity.toolRes`.
         */
        private val CODE_REFERENCED_PREFIXES = listOf(
            "phrase",
            "activity_tool",
        )
    }
}
