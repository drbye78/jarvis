package com.jarvis.assistant

import com.jarvis.assistant.settings.SettingsCategory
import com.jarvis.assistant.settings.SettingsInventory
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.CredentialsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * THE anti-drop guard for the settings redesign.
 *
 * The strangler rewrite may move every control, but a persisted setting that no
 * longer appears anywhere is silently lost to the user. This test reflects over
 * the real accessors and proves every one is accounted for: either it is in
 * [SettingsInventory.entries] (a visible setting) or it is in the explicit
 * [SettingsInventory.nonSettingsKeys] allow-list (`onboarded`, `userStopped`).
 * Adding a new pref without registering it fails HERE, at the source.
 *
 * Reflection is by public zero-arg getters (no kotlin-reflect dependency on the
 * test classpath), which is exactly the public property surface.
 */
class SettingsInventoryTest {

    @Test
    fun `every persisted member is registered or allow-listed`() {
        val reflected = propertyNames(AppPrefs::class.java) + propertyNames(CredentialsStore::class.java)
        val known = SettingsInventory.entries.map { it.key }.toSet() + SettingsInventory.nonSettingsKeys

        val missing = (reflected - known).sorted()
        assertEquals("persisted members MISSING from SettingsInventory: $missing", emptyList<String>(), missing)
    }

    @Test
    fun `the inventory covers both accessors with no stale keys`() {
        val reflected = propertyNames(AppPrefs::class.java) + propertyNames(CredentialsStore::class.java)
        val inventoryKeys = SettingsInventory.entries.map { it.key }.toSet()
        // Reported by this assertion so a drift is obvious ("the count it asserts").
        assertEquals(38, SettingsInventory.entries.size)
        assertEquals(40, reflected.size)
        val stale = inventoryKeys - reflected
        assertTrue("inventory keys not found on any accessor: $stale", stale.isEmpty())
    }

    @Test
    fun `entry keys are unique`() {
        val keys = SettingsInventory.entries.map { it.key }
        assertEquals("duplicate inventory keys: $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `no entry is also an allow-listed non-setting`() {
        val overlap = SettingsInventory.entries.map { it.key }.toSet() intersect SettingsInventory.nonSettingsKeys
        assertTrue("settings also marked as non-settings: $overlap", overlap.isEmpty())
    }

    @Test
    fun `every entry points at a real category`() {
        SettingsInventory.entries.forEach { entry ->
            assertTrue(
                "unknown category for ${entry.key}: ${entry.category}",
                entry.category in SettingsCategory.entries,
            )
        }
    }

    /** Public zero-arg getter names → the Kotlin property names. */
    private fun propertyNames(type: Class<*>): Set<String> =
        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && it.parameterCount == 0 && it.name != "getClass" }
            .mapNotNull { method ->
                val name = method.name
                when {
                    name.length > 3 && name.startsWith("get") -> name.substring(3).decapitalizeFirst()
                    name.length > 2 && name.startsWith("is") &&
                        method.returnType == Boolean::class.javaPrimitiveType -> name.substring(2).decapitalizeFirst()
                    else -> null
                }
            }
            .toSet()

    private fun String.decapitalizeFirst(): String = replaceFirstChar { it.lowercase() }
}
