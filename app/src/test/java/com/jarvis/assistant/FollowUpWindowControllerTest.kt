package com.jarvis.assistant

import com.jarvis.assistant.audio.aec.EnergyVad
import com.jarvis.assistant.session.FollowUpWindowController
import com.jarvis.assistant.audio.aec.AecMode
import com.jarvis.assistant.audio.aec.MicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowUpWindowControllerTest {

    private var now = 0L

    private fun ctrl(windowMs: Long = 5000) =
        FollowUpWindowController(windowMs = windowMs, nowMs = { now })

    @Test
    fun `window opens only after a spoken turn`() {
        val c = ctrl()
        assertNull("no window after silent turn", c.onTurnEnded(spoke = false, enabled = true))
        assertNotNull("window after spoken turn", c.onTurnEnded(spoke = true, enabled = true))
        assertNotNull(c.onTurnEnded(spoke = true, enabled = true))
    }

    @Test
    fun `feature disabled never opens the window`() {
        val c = ctrl()
        assertNull(c.onTurnEnded(spoke = true, enabled = false))
        assertEquals(FollowUpWindowController.ControllerState.IDLE, c.state)
    }

    @Test
    fun `window expires exactly once at the deadline`() {
        val c = ctrl(windowMs = 5000)
        c.onTurnEnded(spoke = true, enabled = true)
        now = 4999
        assertNull("not yet", c.transition())
        now = 5000
        val effect = c.transition()
        assertTrue(effect is FollowUpWindowController.Effect.ExpireWindow)
        assertNull("expire fires once", c.transition())
        assertEquals(FollowUpWindowController.ControllerState.IDLE, c.state)
    }

    @Test
    fun `vad onset triggers a follow-up turn inside the window`() {
        val c = ctrl()
        c.onTurnEnded(spoke = true, enabled = true)
        now = 2000
        val effect = c.onVadActive()
        assertTrue(effect is FollowUpWindowController.Effect.StartFollowUpTurn)
        // Stray VAD after the trigger is ignored.
        assertNull(c.onVadActive())
    }

    @Test
    fun `vad onset outside any window is ignored`() {
        val c = ctrl()
        assertNull(c.onVadActive())
    }

    @Test
    fun `wake word supersedes the window`() {
        val c = ctrl()
        c.onTurnEnded(spoke = true, enabled = true)
        c.onWakeWord()
        now = 10_000
        assertNull("window was closed by wake word", c.transition())
    }

    @Test
    fun `cancellation closes the window`() {
        val c = ctrl()
        c.onTurnEnded(spoke = true, enabled = true)
        c.onCancelled()
        now = 10_000
        assertNull(c.transition())
    }

    @Test
    fun `chained conversation reopens the window after each spoken turn`() {
        val c = ctrl()
        c.onTurnEnded(spoke = true, enabled = true) // window 1
        now = 1000
        c.onVadActive() // follow-up turn
        c.onTurnEnded(spoke = true, enabled = true) // window 2
        now = 6000
        assertTrue(c.transition() is FollowUpWindowController.Effect.ExpireWindow)
    }

    @Test
    fun `remaining fraction counts down and clamps`() {
        val c = ctrl(windowMs = 2000)
        c.onTurnEnded(spoke = true, enabled = true)
        assertEquals(1.0f, c.remainingFraction(), 0.01f)
        now = 1000
        assertEquals(0.5f, c.remainingFraction(), 0.01f)
        now = 5000
        assertEquals(0f, c.remainingFraction(), 0f)
    }

    @Test
    fun `window length is clamped to the supported range`() {
        val c = ctrl()
        c.setWindowMs(99_000)
        c.onTurnEnded(spoke = true, enabled = true)
        now = FollowUpWindowController.MAX_WINDOW_MS - 1
        assertNull("still open just before MAX", c.transition())
        now = FollowUpWindowController.MAX_WINDOW_MS
        assertNotNull("expires exactly at MAX", c.transition())
    }
}

class EnergyVadTest {

    private fun frame(rms: Double, n: Int = 320): ShortArray {
        // Deterministic alternating pattern with the requested RMS.
        val out = ShortArray(n)
        val amp = rms / kotlin.math.sqrt(2.0)
        for (i in 0 until n) out[i] = ((if (i % 2 == 0) amp else -amp)).toInt().toShort()
        return out
    }

    @Test
    fun `onset fires once per burst after the onset streak`() {
        val vad = EnergyVad()
        repeat(20) { vad.process(frame(150.0)) } // adapt floor to silence
        var onsets = 0
        repeat(10) {
            vad.process(frame(3000.0))
            if (vad.onset) onsets++
        }
        assertEquals("exactly one rising edge per burst", 1, onsets)
        assertEquals(EnergyVad.State.ACTIVE, vad.state)
    }

    @Test
    fun `steady music-like level does not trigger onset`() {
        val vad = EnergyVad()
        repeat(20) { vad.process(frame(150.0)) } // floor ≈ 150
        // Level music: 4× floor — under the 6× onset ratio.
        repeat(30) { vad.process(frame(600.0)) }
        assertEquals(EnergyVad.State.SILENT, vad.state)

    }

    @Test
    fun `silence after speech ends with hangover`() {
        val vad = EnergyVad(hangoverFrames = 5, offsetFrames = 4)
        repeat(20) { vad.process(frame(150.0)) }
        repeat(6) { vad.process(frame(3000.0)) }
        assertEquals(EnergyVad.State.ACTIVE, vad.state)
        // Offset streak + hangover keep it active for a while, then SILENT.
        repeat(3) { vad.process(frame(150.0)) }
        assertEquals(EnergyVad.State.ACTIVE, vad.state)
        repeat(10) { vad.process(frame(150.0)) }
        assertEquals(EnergyVad.State.SILENT, vad.state)
    }

    @Test
    fun `noise floor adapts upward when the room gets loud`() {
        val vad = EnergyVad()
        repeat(50) { vad.process(frame(150.0)) }
        val floorQuiet = vad.noiseFloor
        repeat(400) { vad.process(frame(1200.0)) }
        assertTrue("floor must rise: ${vad.noiseFloor}", vad.noiseFloor > floorQuiet * 2)
        // Speech above the NEW floor still triggers.
        var onset = false
        repeat(10) { vad.process(frame(9000.0)); if (vad.onset) onset = true }
        assertTrue(onset)
    }
}

class MicProfileTest {

    @Test
    fun `mode mapping follows the plan`() {
        // MediaRecorder.AudioSource.VOICE_RECOGNITION == 6, VOICE_COMMUNICATION == 7
        val off = MicProfile.forMode(AecMode.OFF)
        assertEquals(6, off.androidAudioSource)
        assertFalse(off.attachHardwareAec)

        val hw = MicProfile.forMode(AecMode.HARDWARE)
        assertEquals(7, hw.androidAudioSource)
        assertTrue(hw.attachHardwareAec)

        val sw = MicProfile.forMode(AecMode.SOFTWARE)
        assertEquals(6, sw.androidAudioSource)
        assertFalse(sw.attachHardwareAec)
    }

    @Test
    fun `prefs parsing is safe and total`() {
        assertEquals(AecMode.OFF, AecMode.fromPref(null))
        assertEquals(AecMode.OFF, AecMode.fromPref("garbage"))
        assertEquals(AecMode.HARDWARE, AecMode.fromPref("hardware"))
        assertEquals(AecMode.SOFTWARE, AecMode.fromPref("software"))
        assertEquals("off", AecMode.toPref(AecMode.OFF))
        assertEquals("hardware", AecMode.toPref(AecMode.HARDWARE))
        assertEquals("software", AecMode.toPref(AecMode.SOFTWARE))
        // Round trip.
        AecMode.entries.forEach { assertEquals(it, AecMode.fromPref(AecMode.toPref(it))) }
    }
}
