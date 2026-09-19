package com.jarvis.assistant

import com.jarvis.assistant.tools.DeviceToolOutcome
import com.jarvis.assistant.tools.DndPanelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device-tool honesty seam: success is only emitted as `status: ok` when
 * the caller actually verified the change, and the API-35 DND branch hands the
 * user to the system settings panel instead of a silent no-op.
 */
class DeviceToolOutcomeTest {

    @Test
    fun `ok carries the tool fields after status`() {
        assertEquals(
            """{"status":"ok","level":42}""",
            DeviceToolOutcome.Ok(listOf("level" to 42)).toJson(),
        )
    }

    @Test
    fun `ok with no fields is just status ok`() {
        assertEquals("""{"status":"ok"}""", DeviceToolOutcome.Ok().toJson())
    }

    @Test
    fun `panel opened carries the detail`() {
        assertEquals(
            """{"status":"panel_opened","detail":"opened"}""",
            DeviceToolOutcome.PanelOpened("opened").toJson(),
        )
    }

    @Test
    fun `unavailable maps to the error object`() {
        assertEquals(
            """{"error":"blocked"}""",
            DeviceToolOutcome.Unavailable("blocked").toJson(),
        )
    }
}

/**
 * API 35 behaviour change: apps targeting SDK 35 can no longer set the global
 * DND filter, so the tool must open the settings panel there while keeping the
 * direct (verified) call below 35.
 */
class DndPanelPolicyTest {

    @Test
    fun `below sdk 35 keeps the direct setInterruptionFilter path`() {
        assertFalse(DndPanelPolicy.usePanel(34))
    }

    @Test
    fun `sdk 35 selects the settings panel path`() {
        assertTrue(DndPanelPolicy.usePanel(35))
    }

    @Test
    fun `panel intent uses the notification policy access action`() {
        assertEquals(
            "android.settings.NOTIFICATION_POLICY_ACCESS_SETTINGS",
            DndPanelPolicy.settingsAction,
        )
    }
}
