package com.jarvis.assistant

import com.jarvis.assistant.session.FollowUpTailGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The follow-up tail gate: proves the assistant's own decaying TTS tail cannot
 * arm the VAD, while a quiet room arms exactly at the lead-in boundary and a
 * permanently loud room still arms via the bounded fallback.
 */
class FollowUpTailGateTest {

    private val floor = 40.0

    @Test
    fun `stays closed while a loud tail is still ringing`() {
        val gate = FollowUpTailGate()
        // The reply's decaying tail, far above the noise floor, for well past
        // the old fixed lead-in. This is exactly the window the phantom turn
        // used to fire in.
        repeat(20) { frame ->
            assertFalse(
                "gate must not open on the tail (frame $frame)",
                gate.onFrame(rms = 1000.0, floor = floor),
            )
        }
        assertFalse(gate.armed)
    }

    @Test
    fun `arms at the lead-in boundary in a quiet room`() {
        val gate = FollowUpTailGate()
        var armedOn = -1
        repeat(30) { i ->
            if (gate.onFrame(rms = 0.0, floor = floor)) armedOn = i + 1
        }
        assertTrue(gate.armed)
        assertEquals(FollowUpTailGate.LEAD_IN_FRAMES, armedOn)
    }

    @Test
    fun `a transient notch in the tail does not arm the gate`() {
        val gate = FollowUpTailGate()
        // Three quiet frames mid-tail, then loud again — the streak must reset.
        repeat(3) { gate.onFrame(rms = 0.0, floor = floor) }
        repeat(12) { gate.onFrame(rms = 1000.0, floor = floor) }
        assertFalse("a momentary dip must not arm the gate", gate.armed)
    }

    @Test
    fun `fallback arms a room that never goes quiet`() {
        val gate = FollowUpTailGate()
        var armedOn = -1
        repeat(FollowUpTailGate.MAX_SETTLE_FRAMES + 5) { i ->
            if (gate.onFrame(rms = 10_000.0, floor = floor)) armedOn = i + 1
        }
        assertTrue("the feature must not be left permanently deaf", gate.armed)
        assertEquals(FollowUpTailGate.MAX_SETTLE_FRAMES, armedOn)
    }

    @Test
    fun `arming fires on exactly one frame`() {
        val gate = FollowUpTailGate()
        val fires = (0 until 30).count { gate.onFrame(rms = 0.0, floor = floor) }
        assertEquals(1, fires)
    }

    @Test
    fun `decay below the arm ratio arms, and the gate then latches`() {
        val gate = FollowUpTailGate()
        // Decaying tail: loud past the lead-in, then below the arm ratio.
        repeat(5) { gate.onFrame(rms = 1000.0, floor = floor) }
        repeat(5) { gate.onFrame(rms = 50.0, floor = floor) }
        assertTrue(gate.armed)
        // Once armed the gate is latched — subsequent frames are the caller's
        // to judge via the VAD, so onFrame reports false.
        assertFalse(gate.onFrame(rms = 50.0, floor = floor))
    }
}
