package com.jarvis.assistant.settings

import com.jarvis.assistant.speech.tts.TtsCapabilities
import kotlin.math.roundToInt

/**
 * Pure derivations for the SPEECH screen's capability-gated controls.
 *
 * Visibility is driven by the ACTIVE backend's declared [TtsCapabilities]
 * ([VoiceCatalog.capabilitiesFor]) rather than an identity check, and the ROLE
 * control additionally requires the selected voice to DOCUMENT at least one
 * role: an undocumented voice/role pair is a hard service error, so the role
 * field is FAIL-CLOSED. Keeping the policy here (not in the controller) makes it
 * unit-testable on the plain JVM, with no Robolectric.
 */
object SpeechControls {

    /** Lowest speaking rate the UI slider exposes (storage accepts a wider range). */
    const val SPEED_UI_MIN = 0.5f

    /** Highest speaking rate the UI slider exposes. */
    const val SPEED_UI_MAX = 2.0f

    /** Slider increment; the value label shows one decimal. */
    const val SPEED_UI_STEP = 0.1f

    /** Service default rate, also the [snapSpeed] fallback for a non-finite input. */
    private const val DEFAULT_SPEED = 1.0f

    /** Role control shows only when the backend supports roles AND the voice documents some. */
    fun showRole(capabilities: TtsCapabilities, documentedRoles: List<String>): Boolean =
        capabilities.roles && documentedRoles.isNotEmpty()

    /** Speed control shows whenever the backend can express a speaking rate at all. */
    fun showSpeed(capabilities: TtsCapabilities): Boolean = capabilities.speed

    /**
     * The advanced-entry count the disclosure label reports. Derived from what is
     * actually visible, so a backend with no role/speed capability (and a Yandex
     * voice that documents no role) collapses the disclosure instead of offering
     * an empty container.
     */
    fun advancedEntryCount(capabilities: TtsCapabilities, documentedRoles: List<String>): Int {
        val role = if (showRole(capabilities, documentedRoles)) 1 else 0
        val speed = if (showSpeed(capabilities)) 1 else 0
        return role + speed
    }

    /**
     * Snaps [speed] onto the slider's `SPEED_UI_MIN + n * SPEED_UI_STEP` grid and
     * clamps it to the UI range. The Material slider rejects a value that is off
     * its step grid, so the stored pref — validated over the WIDER `0.1..3.0`
     * range — must be snapped before seeding the control. A non-finite value
     * degrades to the service default.
     */
    fun snapSpeed(speed: Float): Float {
        if (!speed.isFinite()) return DEFAULT_SPEED
        val clamped = speed.coerceIn(SPEED_UI_MIN, SPEED_UI_MAX)
        val steps = ((clamped - SPEED_UI_MIN) / SPEED_UI_STEP).roundToInt()
        return (SPEED_UI_MIN + steps * SPEED_UI_STEP).coerceIn(SPEED_UI_MIN, SPEED_UI_MAX)
    }
}
