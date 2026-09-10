package com.jarvis.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wake-word radio ↔ pref mapping (imported-word fix): the radio has two
 * options but the pref has three values; an imported .ppn (`custom_user`)
 * must restore onto the custom radio and survive re-selection instead of
 * being silently downgraded to `custom_bundled`.
 */
class WakeWordModelUiTest {

    @Test
    fun `builtin restores onto the builtin radio`() {
        assertTrue(WakeWordModelUi.isBuiltinRadio("builtin"))
    }

    @Test
    fun `both custom flavors restore onto the custom radio`() {
        assertFalse(WakeWordModelUi.isBuiltinRadio("custom_bundled"))
        assertFalse(WakeWordModelUi.isBuiltinRadio("custom_user"))
    }

    @Test
    fun `selecting the builtin radio always writes builtin`() {
        assertEquals(
            "builtin",
            WakeWordModelUi.modelForSelection(customSelected = false, importedPpnPath = null),
        )
        assertEquals(
            "builtin",
            WakeWordModelUi.modelForSelection(
                customSelected = false,
                importedPpnPath = "/data/user/0/app/files/user_wake.ppn",
            ),
        )
    }

    @Test
    fun `custom radio with no import maps to the bundled keyword set`() {
        assertEquals(
            "custom_bundled",
            WakeWordModelUi.modelForSelection(customSelected = true, importedPpnPath = null),
        )
        assertEquals(
            "custom_bundled",
            WakeWordModelUi.modelForSelection(customSelected = true, importedPpnPath = ""),
        )
        assertEquals(
            "custom_bundled",
            WakeWordModelUi.modelForSelection(customSelected = true, importedPpnPath = "   "),
        )
    }

    @Test
    fun `custom radio keeps an imported ppn instead of downgrading it`() {
        // The regression: builtin → custom round-trip used to overwrite
        // custom_user with custom_bundled, silently disabling the import.
        assertEquals(
            "custom_user",
            WakeWordModelUi.modelForSelection(
                customSelected = true,
                importedPpnPath = "/data/user/0/app/files/user_wake.ppn",
            ),
        )
    }
}
