package com.jarvis.assistant.settings

import com.jarvis.assistant.speech.SpeechBackend
import com.jarvis.assistant.speech.tts.TtsCapabilities
import com.jarvis.assistant.speech.tts.VoiceCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure policy behind the SPEECH screen's capability-gated controls.
 *
 * The controller itself has no Robolectric coverage, so these tests are the
 * safety net for the two rules that are easy to regress: visibility follows the
 * backend's DECLARED capabilities (never its identity), and the role control is
 * fail-closed on the selected voice's documented roles.
 */
class SpeechControlsTest {

    private val yandex = VoiceCatalog.capabilitiesFor(SpeechBackend.YANDEX)
    private val sber = VoiceCatalog.capabilitiesFor(SpeechBackend.SBER)

    @Test
    fun `role control needs both the capability and a documenting voice`() {
        assertTrue(SpeechControls.showRole(yandex, listOf("neutral")))
        // Fail-closed: a voice that documents no roles offers none.
        assertFalse(SpeechControls.showRole(yandex, emptyList()))
        // A backend with no role capability never shows the control, voice or not.
        assertFalse(SpeechControls.showRole(sber, listOf("neutral")))
    }

    @Test
    fun `speed control follows the backend capability alone`() {
        assertTrue(SpeechControls.showSpeed(yandex))
        assertFalse(SpeechControls.showSpeed(sber))
    }

    @Test
    fun `advanced count reflects only the controls actually shown`() {
        assertEquals(2, SpeechControls.advancedEntryCount(yandex, listOf("neutral")))
        // A Yandex voice that documents no role leaves only the speed control.
        assertEquals(1, SpeechControls.advancedEntryCount(yandex, emptyList()))
        assertEquals(0, SpeechControls.advancedEntryCount(sber, listOf("neutral")))
    }

    @Test
    fun `explicit capabilities drive visibility without backend identity`() {
        val neither = TtsCapabilities(roles = false, speed = false)
        val both = TtsCapabilities(roles = true, speed = true)
        assertFalse(SpeechControls.showRole(neither, listOf("neutral")))
        assertFalse(SpeechControls.showSpeed(neither))
        assertTrue(SpeechControls.showRole(both, listOf("neutral")))
        assertTrue(SpeechControls.showSpeed(both))
    }

    @Test
    fun `snapSpeed clamps to the UI range and snaps onto the step grid`() {
        // Below/above the UI range clamps to its ends (storage allows 0.1..3.0).
        assertEquals(0.5f, SpeechControls.snapSpeed(0.0f), 0.0001f)
        assertEquals(2.0f, SpeechControls.snapSpeed(3.0f), 0.0001f)
        // On-grid values pass through; off-grid values snap to the nearest step.
        assertEquals(1.0f, SpeechControls.snapSpeed(1.0f), 0.0001f)
        assertEquals(1.1f, SpeechControls.snapSpeed(1.14f), 0.0001f)
        assertEquals(0.9f, SpeechControls.snapSpeed(0.86f), 0.0001f)
    }

    @Test
    fun `snapSpeed degrades a non-finite rate to the default`() {
        assertEquals(1.0f, SpeechControls.snapSpeed(Float.NaN), 0.0001f)
        assertEquals(1.0f, SpeechControls.snapSpeed(Float.POSITIVE_INFINITY), 0.0001f)
    }
}
