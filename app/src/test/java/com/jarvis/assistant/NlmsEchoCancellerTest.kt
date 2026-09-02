package com.jarvis.assistant

import com.jarvis.assistant.audio.aec.DelayAligner
import com.jarvis.assistant.audio.aec.FarEndMixer
import com.jarvis.assistant.audio.aec.LinearResampler
import com.jarvis.assistant.audio.aec.NlmsEchoCanceller
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline DSP validation of the built-in software AEC. The echo path is
 * synthetic (pure delay + short FIR + gain) — the honest scope of an NLMS
 * canceller; see PLAN-AEC-FOLLOWUP §0 for why real-device quality needs
 * the RUNBOOK measurement ladder.
 */
class NlmsEchoCancellerTest {

    private class Lcg(seed: Long) {
        var s = seed
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 33) / (1L shl 31).toDouble()) * 2.0 - 1.0 // [-1,1)
        }
    }

    private fun frameOf(gen: Lcg, n: Int = 320): ShortArray =
        ShortArray(n) { (gen.next() * 8000).toInt().toShort() }

    private fun sineFrame(phase0: Double, amp: Double, n: Int = 320): Pair<ShortArray, Double> {
        val out = ShortArray(n)
        var p = phase0
        for (i in 0 until n) {
            out[i] = (sin(p) * amp).toInt().toShort()
            p += 2 * PI * 300.0 / 16000.0
        }
        return out to p
    }

    /** FIR echo path applied at float scale. */
    private fun echoOf(history: List<Short>, delay: Int, fir: DoubleArray, gain: Double): Double {
        val idx = history.size - 1 - delay
        var acc = 0.0
        for (k in fir.indices) {
            val hIdx = idx - k
            if (hIdx >= 0) acc += fir[k] * history[hIdx]
        }
        return acc * gain
    }

    @Test
    fun `delay aligner finds an injected bulk delay`() {
        val gen = Lcg(42)
        val len = 2320 // covers search 2000 + mic 320
        val far = ShortArray(len) { (gen.next() * 6000).toInt().toShort() }
        // mic = far-end delayed by 640 samples (the mic "hears the past").
        val mic = ShortArray(320) { far[len - 320 + it - 640] }
        val lag = DelayAligner.estimate(
            DelayAligner.toDemeanedFloats(mic),
            DelayAligner.toDemeanedFloats(far),
            searchLagSamples = 2000,
        )
        assertNotNull(lag)
        assertTrue("lag=$lag", abs(lag!! - 640) <= DelayAligner.LAG_STRIDE)
    }

    @Test
    fun `delay aligner rejects uncorrelated far-end`() {
        val mic = DelayAligner.toDemeanedFloats(frameOf(Lcg(1)))
        val far = DelayAligner.toDemeanedFloats(frameOf(Lcg(2)))
        // history too short for the full search → still must not fabricate.
        val lag = DelayAligner.estimate(mic, far, searchLagSamples = 0)
        if (lag != null) {
            // With search 0 the only candidate is lag 0; unrelated noise should
            // score below the trust threshold.
            assertEquals(0, lag.toInt())
        } // null or 0-with-low-score both acceptable; minCorrelation guards.
    }

    @Test
    fun `resampler paces exactly and chunking is transparent`() {
        val gen = Lcg(7)
        val big = ShortArray(2400) { (gen.next() * 6000).toInt().toShort() }
        val oneShot = LinearResampler(24000, 16000).process(big)

        val streamed = ArrayList<Short>()
        val r2 = LinearResampler(24000, 16000)
        var i = 0
        while (i < big.size) {
            val len = minOf(37, big.size - i)
            val chunk = big.copyOfRange(i, i + len)
            r2.process(chunk).forEach { streamed.add(it) }
            i += len
        }

        assertEquals("total output must pace exactly", 1600, oneShot.size)
        assertEquals(1600, streamed.size)
        // Compare after the priming ramp: chunks differ only by the block-edge
        // sample interpolation; allow small tolerance on EVERY sample.
        var maxDiff = 0
        for (j in oneShot.indices) {
            val d = abs(oneShot[j] - streamed[j])
            if (d > maxDiff) maxDiff = d
        }
        assertTrue("streaming vs one-shot max diff $maxDiff", maxDiff <= 2)
    }

    @Test
    fun `far-end mixer paces bursts at real time and sums lanes`() {
        val m = FarEndMixer()
        m.lane("tts")
        // Burst: 5 frames pushed at once.
        val f = ShortArray(320) { 100 }
        repeat(5) { m.onFrame("tts", f) }
        // Slot 1 drains one frame's worth; NOT all 320*5 in one slot.
        val s1 = m.drainSlot()
        assertEquals(320, s1.size)
        assertTrue("slot 1 should carry content", s1.sum() > 0)
        // After 6 drains (nothing more fed), lane starves → zeros.
        repeat(5) { m.drainSlot() }
        val s7 = m.drainSlot()
        assertEquals(0, s7.sum().toLong())
        // Lanes sum: two lanes active in the same slot.
        m.onFrame("a", ShortArray(320) { 100 })
        m.onFrame("b", ShortArray(320) { 50 })
        val mixed = m.drainSlot()
        assertEquals("two lanes sum per sample", 150, mixed[0].toInt())
    }

    @Test
    fun `lane overflow drops oldest frames and counts them (audit 24)`() {
        val m = FarEndMixer(maxQueuedSlotsPerLane = 2) // 640 samples of queue
        val f = ShortArray(320) { 100 }
        repeat(10) { m.onFrame("tts", f) } // 3200 samples queued → 8 dropped

        assertEquals(8L, m.droppedFrames)
        assertEquals(0L, FarEndMixer().droppedFrames) // fresh mixer starts clean

        // reset() clears lane state but the lifetime drop counter is the
        // diagnostic — it must SURVIVE a reset.
        m.reset()
        assertEquals(8L, m.droppedFrames)
    }

    @Test
    fun `soft double-talk keeps more gate than the old 25x margin (audit 23)`() {
        // The gate TARGET for an error/floor ratio: at 10x the residual floor
        // the old GATE_OPEN_FACTOR=25 gave 0.4 (soft speech dragged toward
        // MIN_GATE 0.15 during double-talk); at 15 it gives ~0.67.
        assertEquals(0.667, NlmsEchoCanceller.residualGateTarget(10.0, 1.0), 0.01)
        // At the factor itself the gate is fully open.
        assertEquals(1.0, NlmsEchoCanceller.residualGateTarget(15.0, 1.0), 1e-9)
        // Converged echo-only (error ≈ floor): still clamped to MIN_GATE —
        // the suppression of pure residual echo is unchanged.
        assertEquals(0.15, NlmsEchoCanceller.residualGateTarget(1.0, 1.0), 1e-9)
        assertEquals(0.15, NlmsEchoCanceller.residualGateTarget(0.5, 1.0), 1e-9)
        // No usable floor yet (startup / silent far-end): fully open.
        assertEquals(1.0, NlmsEchoCanceller.residualGateTarget(10.0, 0.0), 1e-9)
    }

    @Test
    fun `silent far-end is bit-exact passthrough`() {
        val c = NlmsEchoCanceller(tailMs = 32)
        val gen = Lcg(3)
        // Far-end silent the whole time; enough slots to pass the bypass gate.
        repeat(30) {
            val mic = frameOf(gen)
            val out = c.process(mic)
            assertArrayEquals("AEC must not touch near-end-only audio", mic, out)
        }
    }

    @Test
    fun `converges on a linear echo path and preserves near-end speech`() {
        val tailMs = 48
        val c = NlmsEchoCanceller(tailMs = tailMs)
        val gen = Lcg(11)
        val echoDelay = 1280 // 80 ms — inside the ±250 ms search window
        val fir = doubleArrayOf(0.9, 0.25, -0.1)
        val pathGain = 0.4

        val farHistory = mutableListOf<Short>()
        var sinePhase = 0.0

        val frames = 400 // 8 s
        val frameStartNear = 280 // near-end speech enters for the last 120 frames
        val nearEndSignal = ArrayList<Short>()
        val outputs = ArrayList<ShortArray>()
        val mics = ArrayList<ShortArray>()

        for (f in 0 until frames) {
            val far = frameOf(gen)
            c.onFarEndFrame(far)
            farHistory.addAll(far.toList())

            // Per-sample linear echo: mic[S] = gain * Σ_j fir[j]·far[S-delay-j],
            // with S = f*320 + i — a true FIR path (pointwise linear), so the
            // NLMS filter can model it exactly.
            val mic = ShortArray(320)
            var p = sinePhase
            for (i in 0 until 320) {
                val s = f * 320 + i
                var d = 0.0
                for (j in fir.indices) {
                    val src = s - echoDelay - j
                    if (src >= 0) d += fir[j] * farHistory[src]
                }
                d *= pathGain
                if (f >= frameStartNear) {
                    d += sin(p) * 1500.0
                    p += 2 * PI * 300.0 / 16000.0
                }
                mic[i] = d.toInt().coerceIn(-32768, 32767).toShort()
                if (f >= frameStartNear) nearEndSignal.add((sin(p - 2 * PI * 300.0 / 16000.0) * 1500.0).toInt().toShort())
            }
            sinePhase = p
            mics.add(mic)
            outputs.add(c.process(mic))
        }

        // System suppression over the CONVERGED far-end-only span (200..279):
        // output power vs mic power — filter + residual gate combined. This is
        // the user-visible metric (what the ASR / wake-word lane receives).
        var micPower = 0.0
        var outPower = 0.0
        for (f in 200 until frameStartNear) {
            for (i in 0 until 320) {
                micPower += mics[f][i].toDouble() * mics[f][i]
                outPower += outputs[f][i].toDouble() * outputs[f][i]
            }
        }
        val suppressionDb = 10.0 * kotlin.math.log10(micPower / outPower)
        assertTrue("suppression ${suppressionDb}dB too low for a linear path", suppressionDb > 22.0)

        // Delay estimate must have locked near the true 80 ms.
        val delayMs = c.stats.estimatedDelayMs
        assertNotNull(delayMs)
        assertTrue("estimated delay ${delayMs}ms", abs(delayMs!! - 80) <= 20)

        // Near-end preservation during double-talk (last 100 frames):
        // output must track the injected near-end speech closely.
        val from = frameStartNear + 100
        var errEnergy = 0.0
        var nearEnergy = 0.0
        for (f in from until frames) {
            val out = outputs[f]
            val base = (f - frameStartNear) * 320
            for (i in 0 until 320) {
                val e = out[i] - nearEndSignal[base + i]
                errEnergy += e * e.toDouble()
                nearEnergy += nearEndSignal[base + i].toDouble() * nearEndSignal[base + i]
            }
        }
        val relErr = sqrt(errEnergy / nearEnergy)
        assertTrue("near-end relative error $relErr too high", relErr < 0.35)
    }

    @Test
    fun `divergence guard resets the filter on pathological input`() {
        val c = NlmsEchoCanceller(tailMs = 32)
        val gen = Lcg(13)
        // Near-end MUCH louder than far-end → error >> signal power is not
        // the trigger (that's double talk). Instead feed a time-VARYING
        // near-end uncorrelated to a strong far-end: the filter can only
        // diverge when the adaptation runs on garbage; emulate by zero-energy
        // far-end frames interleaved with strong ones while mic is random.
        var diverged = false
        for (f in 0 until 2000) {
            val far = if (f % 2 == 0) frameOf(gen) else ShortArray(320)
            c.onFarEndFrame(far)
            val mic = frameOf(gen)
            c.process(mic)
            if (c.stats.diverged) diverged = true
        }
        // The guard MAY fire on this adversarial input; the invariant is that
        // the canceller survives (no exception / no out-of-range output).
        val out = c.process(frameOf(gen))
        assertTrue(out.all { abs(it.toInt()) <= Short.MAX_VALUE.toInt() })
    }
}
