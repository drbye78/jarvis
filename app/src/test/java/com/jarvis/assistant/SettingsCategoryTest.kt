package com.jarvis.assistant

import com.jarvis.assistant.settings.SettingsCategory
import com.jarvis.assistant.settings.SettingsInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen registry is the list host's only source of truth, so every
 * resource must be real and distinct — a duplicate layout id would render the
 * wrong screen, a zero title would render a blank row.
 */
class SettingsCategoryTest {

    @Test
    fun `all eight categories are registered`() {
        assertEquals(8, SettingsCategory.entries.size)
    }

    @Test
    fun `ids are unique and match the lowercase enum name`() {
        val ids = SettingsCategory.entries.map { it.id }
        assertEquals("duplicate category ids: $ids", ids.size, ids.toSet().size)
        SettingsCategory.entries.forEach { category ->
            assertEquals(category.name.lowercase(), category.id)
        }
    }

    @Test
    fun `every layout resource is unique and non-zero`() {
        val layouts = SettingsCategory.entries.map { it.layoutRes }
        assertTrue("a zero layout resource id: $layouts", layouts.none { it == 0 })
        assertEquals("duplicate layout resources: $layouts", layouts.size, layouts.toSet().size)
    }

    @Test
    fun `every title and subtitle resource is non-zero`() {
        val titles = SettingsCategory.entries.map { it.titleRes }
        val subtitles = SettingsCategory.entries.map { it.subtitleRes }
        assertTrue("a zero title resource id: $titles", titles.none { it == 0 })
        assertTrue("a zero subtitle resource id: $subtitles", subtitles.none { it == 0 })
        assertEquals("duplicate title resources: $titles", titles.size, titles.toSet().size)
        assertEquals("duplicate subtitle resources: $subtitles", subtitles.size, subtitles.toSet().size)
    }

    @Test
    fun `every category is used by the inventory`() {
        // A category with no entries is a dead screen in the list.
        val used = SettingsInventory.entries.map { it.category }.toSet()
        assertEquals(SettingsCategory.entries.toSet(), used)
    }
}
