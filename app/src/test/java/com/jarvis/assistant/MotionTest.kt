package com.jarvis.assistant

import com.jarvis.assistant.ui.Motion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure reduced-motion decision. The Android glue around it
 * (`ContentObserver` registration, animator suppression inside
 * `VoiceOrbView`) cannot be exercised on the JVM; the policy can, and the
 * policy is what the accessibility fix actually depends on.
 */
class MotionTest {

    @Test
    fun `animators enabled and full scale animates`() {
        assertTrue(Motion.shouldAnimate(animatorsEnabled = true, durationScale = 1f))
    }

    @Test
    fun `framework switch off never animates`() {
        assertFalse(Motion.shouldAnimate(animatorsEnabled = false, durationScale = 1f))
    }

    @Test
    fun `zero duration scale never animates`() {
        // "Remove animations" zeroes the scale; a zero-duration animator still
        // posts frames, so this must read as no-animation, not instant-animation.
        assertFalse(Motion.shouldAnimate(animatorsEnabled = true, durationScale = 0f))
    }

    @Test
    fun `both inputs off never animates`() {
        assertFalse(Motion.shouldAnimate(animatorsEnabled = false, durationScale = 0f))
    }

    @Test
    fun `fractional scales still animate`() {
        // 0.5x / 2x are speed preferences, not a request to stop moving.
        assertTrue(Motion.shouldAnimate(animatorsEnabled = true, durationScale = 0.5f))
        assertTrue(Motion.shouldAnimate(animatorsEnabled = true, durationScale = 2f))
    }

    @Test
    fun `duration tokens are positive`() {
        assertTrue(Motion.TRANSCRIPT_INSERT_MS > 0L)
        assertTrue(Motion.PILL_CROSSFADE_MS > 0L)
        assertTrue(Motion.RINGING_PULSE_MS > 0L)
        assertTrue(Motion.WINDOW_ENTER_MS > 0L)
    }
}
