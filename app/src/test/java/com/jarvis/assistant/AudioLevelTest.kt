package com.jarvis.assistant

import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.ui.AudioLevel
import com.jarvis.assistant.ui.AudioLevelMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure RMS -> smoothed -> normalised mapping behind the orb's
 * loudness response. The View drawing cannot be exercised on the JVM, but the
 * maths that decides "does this look like speech or like noise" can — and it is
 * exactly the part that would silently regress (a linear map looks plausible
 * yet renders everything maxed out or dead).
 */
class AudioLevelTest {

    private val frameSamples = 320

    private fun frame(amplitude: Int) = ShortArray(frameSamples) { amplitude.toShort() }

    // ---- RMS ----

    @Test
    fun `digital silence has zero rms`() {
        assertEquals(0f, AudioLevel.rms(frame(0)), 0f)
    }

    @Test
    fun `empty frame is silence not a crash`() {
        assertEquals(0f, AudioLevel.rms(ShortArray(0)), 0f)
    }

    @Test
    fun `full-scale frame has rms one`() {
        // Short.MIN_VALUE is -32768, i.e. |amplitude| = 1.0 after normalisation.
        assertEquals(1f, AudioLevel.rms(frame(Short.MIN_VALUE.toInt())), 0.0001f)
    }

    @Test
    fun `rms is sign-independent`() {
        assertEquals(AudioLevel.rms(frame(12_000)), AudioLevel.rms(frame(-12_000)), 0.0001f)
    }

    // ---- perceptual (dB) mapping ----

    @Test
    fun `silence normalises to zero`() {
        assertEquals(0f, AudioLevel.normalize(0f), 0f)
        assertEquals(0f, AudioLevel.normalize(-1f), 0f)
    }

    @Test
    fun `sub-floor hiss is treated as silence`() {
        // -60 dBFS: below the -55 dBFS gate, so a quiet room reads as 0.
        assertEquals(0f, AudioLevel.normalize(0.001f), 0f)
    }

    @Test
    fun `loud frame saturates high`() {
        assertEquals(1f, AudioLevel.normalize(AudioLevel.rms(frame(20_000))), 0f)
    }

    @Test
    fun `mid-level frame lands mid-scale rather than pinned`() {
        // A linear mapping would put this near zero; the dB map puts it mid.
        val level = AudioLevel.normalize(AudioLevel.rms(frame(1_000)))
        assertTrue("expected a mid-scale level, got $level", level in 0.4f..0.8f)
    }

    @Test
    fun `mapping is monotonic`() {
        val quiet = AudioLevel.normalize(AudioLevel.rms(frame(300)))
        val mid = AudioLevel.normalize(AudioLevel.rms(frame(1_000)))
        val loud = AudioLevel.normalize(AudioLevel.rms(frame(8_000)))
        assertTrue(quiet < mid)
        assertTrue(mid < loud)
    }

    @Test
    fun `normalisation never leaves the unit range`() {
        assertEquals(0f, AudioLevel.normalize(-5f), 0f)
        assertEquals(1f, AudioLevel.normalize(10f), 0f)
        for (amplitude in listOf(0, 1, 100, 1_000, 20_000, 32_767)) {
            val level = AudioLevel.normalize(AudioLevel.rms(frame(amplitude)))
            assertTrue("amplitude $amplitude -> $level", level in 0f..1f)
        }
    }

    // ---- attack / release ----

    @Test
    fun `attack is faster than release`() {
        val rise = AudioLevel.smooth(previous = 0f, target = 1f, dtMs = 20f)
        val releaseFall = 1f - AudioLevel.smooth(previous = 1f, target = 0f, dtMs = 20f)
        // A 20 ms frame must close far more of a rising gap than a falling one.
        assertTrue("rise $rise vs fall $releaseFall", rise > releaseFall * 3f)
    }

    @Test
    fun `attack and release constants match the intended envelope`() {
        // Fast attack: one 20 ms frame covers ~39% of a step.
        assertTrue(AudioLevel.smooth(0f, 1f, 20f) > 0.35f)
        // Slow release: one 20 ms frame covers under 12% of the fall.
        assertTrue(1f - AudioLevel.smooth(1f, 0f, 20f) < 0.12f)
    }

    @Test
    fun `a longer frame advances further`() {
        // Frame-rate independence: doubling dt doubles the progress, it does
        // not change the time constant.
        assertTrue(AudioLevel.smooth(0f, 1f, 40f) > AudioLevel.smooth(0f, 1f, 20f))
    }

    @Test
    fun `smoothing is clamped to the unit range`() {
        assertTrue(AudioLevel.smooth(0.5f, 5f, 10_000f) <= 1f)
        assertTrue(AudioLevel.smooth(0.5f, -5f, 10_000f) >= 0f)
    }

    // ---- meter ----

    @Test
    fun `meter rises on speech and decays on silence`() {
        val meter = AudioLevelMeter()
        val loud = frame(20_000)
        val quiet = frame(0)

        val firstLoud = meter.onFrame(loud, 20f)
        assertTrue("first loud frame should move the level", firstLoud > 0.3f)

        repeat(10) { meter.onFrame(loud, 20f) }
        val settled = meter.level
        assertTrue("level should approach full on sustained speech, got $settled", settled > 0.9f)

        val afterOneSilent = meter.onFrame(quiet, 20f)
        assertTrue("release must not snap to zero", afterOneSilent > 0.5f)
        assertTrue(afterOneSilent < settled)

        repeat(40) { meter.onFrame(quiet, 20f) }
        assertTrue("silence should decay to near zero", meter.level < 0.05f)
    }

    @Test
    fun `meter reset returns to silence`() {
        val meter = AudioLevelMeter()
        repeat(5) { meter.onFrame(frame(20_000), 20f) }
        assertTrue(meter.level > 0f)
        meter.reset()
        assertEquals(0f, meter.level, 0f)
    }

    // ---- capture-state gate ----

    @Test
    fun `only capture states meter the mic`() {
        assertTrue(AudioLevel.capturesAudio(AssistantState.LISTENING))
        assertTrue(AudioLevel.capturesAudio(AssistantState.FOLLOW_UP_WINDOW))
        assertFalse(AudioLevel.capturesAudio(AssistantState.IDLE))
        assertFalse(AudioLevel.capturesAudio(AssistantState.THINKING))
        assertFalse(AudioLevel.capturesAudio(AssistantState.SPEAKING))
        assertFalse(AudioLevel.capturesAudio(null))
    }
}
