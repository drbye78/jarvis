package com.jarvis.assistant.audio.aec

/**
 * Built-in software echo canceller: normalized-LMS adaptive filter with
 * cross-correlation bulk-delay alignment and a conservative residual
 * suppression gate.
 *
 * Pipeline per 20 ms mic frame ([process], producer thread):
 *
 *  1. [FarEndMixer.drainSlot] pulls one slot of far-end reference onto the
 *     far-end ring (float history, 8192 samples = 512 ms).
 *  2. Every [ESTIMATE_INTERVAL_MS] of far-end activity, [DelayAligner]
 *     re-estimates the bulk delay far-end→mic (±250 ms window). A delay
 *     change larger than the tap stride resets the weights (the filter
 *     re-converges at the new alignment).
 *  3. NLMS: e[n] = d[n] − Σ w[k]·x[n − delay − k], w updated per sample
 *     with a normalized step (μ/[‖x‖² + ε]) and leak regularization.
 *  4. Residual suppression ("NLP-lite"): while the far-end is audible and
 *     the filter is converged, the output gain follows the smoothed
 *     error/mic power ratio with a hard floor — protects against residual
 *     echo but never gates near-end speech (double-talk raises the ratio
 *     and releases the gain).
 *  5. Far-end silent for > [BYPASS_SILENCE_MS] ⇒ bit-exact passthrough —
 *     the canceller NEVER touches near-end-only audio.
 *
 * Honesty (PLAN-AEC-FOLLOWUP §0): this is a linear-filter canceller, NOT
 * WebRTC AEC3 — typical ERLE is 15–30 dB for linear echo paths; nonlinear
 * speaker distortion of cheap tablet speakers limits the achievable
 * suppression. The [EchoCanceller] interface is the drop-in slot for a
 * native AEC3 when one becomes linkable.
 */
class NlmsEchoCanceller(
    private val sampleRate: Int = 16_000,
    /** Adaptive-filter echo-path tail coverage, ms (default 96 ms = 1536 taps). */
    tailMs: Int = 96,
    /** NLMS step size (0..1); 0.35 converges fast with the leak below. */
    private val stepSize: Double = 0.35,
    /** Coefficient leak per sample (regularization against divergence). */
    private val leak: Double = 1e-9,
    /** Minimum far-end energy (RMS², int16 scale) to count as "active". */
    private val farEndActiveEnergy: Double = 320.0 * 9.0,
) : EchoCanceller {

    private val taps = (sampleRate * tailMs) / 1000

    // ---- far-end ring (float, power-of-two for masked indexing) ----
    private val ring = FloatArray(RING_SAMPLES)
    private var ringWrite = 0
    private var ringFilled = 0

    // ---- adaptive state ----
    private val weights = DoubleArray(taps)
    private var delaySamples = -1 // -1 = unknown yet
    private var delayConfident = false

    // ---- bookkeeping ----
    private var farEndSilentSlots = Int.MAX_VALUE / 2
    private var slotsSinceEstimate = Int.MAX_VALUE / 2
    private var micPowerSum = 0.0
    private var errorPowerSum = 0.0
    private var erleSamples = 0.0
    private var divergedFlag = false
    private var adaptingFlag = false
    private var divergeStreak = 0

    private val mixer = FarEndMixer(frameSamples)

    /** NLP: min-tracked residual error power floor (see gate step 6). Starts
     *  at +∞ so the FIRST far-active frame seeds it with the actual error. */
    private var residFloor = Double.MAX_VALUE

    /** DTD: previous frame's error power (one-frame-lag freeze decision). */
    private var lastFrameErrPower = 0.0

    /** Contiguous frozen-adaptation frames (path-change reseed timer). */
    private var frozenStreak = 0

    /** NLP: smoothed gate gain (1.0 = fully open). */
    private var gateSmooth = 1.0

    override fun onFarEndFrame(frame: ShortArray, atNanos: Long) {
        mixer.onFrame(LANE_DEFAULT, frame)
    }

    /** Dedicated-lane feed (TTS tap vs playback capture can be distinct). */
    fun onFarEndFrame(laneId: String, frame: ShortArray) {
        mixer.onFrame(laneId, frame)
    }

    override fun reset() {
        ring.fill(0f)
        ringWrite = 0
        ringFilled = 0
        weights.fill(0.0)
        delaySamples = -1
        delayConfident = false
        farEndSilentSlots = Int.MAX_VALUE / 2
        slotsSinceEstimate = Int.MAX_VALUE / 2
        micPowerSum = 0.0
        errorPowerSum = 0.0
        erleSamples = 0.0
        divergedFlag = false
        adaptingFlag = false
        divergeStreak = 0
        frozenStreak = 0
        residFloor = Double.MAX_VALUE
        gateSmooth = 1.0
        lastFrameErrPower = 0.0
        mixer.reset()
    }

    override val stats: EchoCanceller.Stats
        get() = EchoCanceller.Stats(
            estimatedDelayMs = if (delaySamples >= 0) (delaySamples * 1000L) / sampleRate else null,
            erleDb = if (micPowerSum > 1e-6 && errorPowerSum > 1e-6) {
                10.0 * Math.log10(micPowerSum / errorPowerSum)
            } else null,
            adapting = adaptingFlag,
            diverged = divergedFlag,
            gateGain = gateSmooth.toFloat().coerceIn(MIN_GATE.toFloat(), 1f),
            errorToFloor = if (residFloor < Double.MAX_VALUE && residFloor > 0 && lastFrameErrPower > 0) {
                lastFrameErrPower / residFloor
            } else null,
        )

    override fun process(micFrame: ShortArray): ShortArray {
        require(micFrame.size == frameSamples) { "expected $frameSamples-sample frames, got ${micFrame.size}" }

        // 1) Advance the far-end grid by one slot.
        val farSlot = mixer.drainSlot()
        for (s in farSlot) pushRing(s.toFloat() / 32768f)
        val farEnergy = mixer.lastSlotEnergy

        val farActive = farEnergy > farEndActiveEnergy
        if (farActive) {
            farEndSilentSlots = 0
            slotsSinceEstimate++
        } else {
            farEndSilentSlots++
        }

        // 2) Bypass: far-end silent long enough — pass through bit-exact.
        if (farEndSilentSlots > BYPASS_SLOTS) {
            adaptingFlag = false
            return micFrame
        }

        // 3) Periodic delay re-estimation during far-end activity.
        if (farActive && slotsSinceEstimate >= ESTIMATE_INTERVAL_SLOTS) {
            slotsSinceEstimate = 0
            maybeReestimateDelay(micFrame)
        }

        // 4) NLMS over the frame.
        val d = FloatArray(frameSamples) { micFrame[it] / 32768f }
        val out = FloatArray(frameSamples)
        var frameMicPower = 0.0
        var frameErrPower = 0.0

        val delay = if (delaySamples >= 0) delaySamples else 0
        val xNorm = ringInputPower(delay)
        val mu = if (xNorm > MIN_INPUT_POWER) stepSize / (xNorm + REG_EPS) else 0.0

        // Double-talk detection (one-frame lag): freeze adaptation while the
        // error is far above the residual floor the converged filter achieves
        // on echo alone — that excess IS near-end speech, and adapting on it
        // would corrupt the weights (NLMS misadjustment).
        val adapt = mu > 0.0 && lastFrameErrPower <= residFloor * DTD_FACTOR

        // Path-change recovery: adaptation frozen for FREEZE_RESEED_SLOTS of
        // CONTIGUOUS far-end activity re-seeds the floor once (either the echo
        // path genuinely changed → adaptation resumes, or it is sustained
        // double-talk → the error re-freezes it within 1–2 frames; the single
        // re-allowed adaptation frame causes only a bounded weight kick).
        if (!adapt && farActive) {
            frozenStreak++
            if (frozenStreak >= FREEZE_RESEED_SLOTS) {
                residFloor = lastFrameErrPower
                frozenStreak = 0
            }
        } else {
            frozenStreak = 0
        }

        for (i in 0 until frameSamples) {
            // e = d - w·x  (x indexed relative to current ring end, delayed)
            var estimate = 0.0
            var x0 = ringIndex(i, delay)
            var k = 0
            while (k < taps) {
                estimate += weights[k] * ring[x0]
                x0 = nextRingIndex(x0) // x[n-k] walks BACK in time
                k++
            }
            val e = (d[i] - estimate).toFloat()
            out[i] = e

            // NLMS update with leak (frozen during detected double-talk).
            if (adapt) {
                x0 = ringIndex(i, delay)
                k = 0
                val g = mu * e
                while (k < taps) {
                    weights[k] = weights[k] * (1.0 - leak) + g * ring[x0]
                    x0 = nextRingIndex(x0)
                    k++
                }
            }

            // Push this mic sample's slot onto the ring so subsequent samples
            // can reference it? NO — the ring is far-end-only; the delayed x
            // window for sample i is fixed by (ring end + i + delay). ringIndex
            // accounts for the frame position; see ringIndex().
            frameMicPower += d[i].toDouble() * d[i]
            frameErrPower += e.toDouble() * e
        }

        // 5) Divergence guard: error larger than the signal itself for a
        //    sustained stretch ⇒ reset weights (the filter went bad, e.g.
        //    wrong delay after a path change).
        if (frameErrPower > frameMicPower * DIVERGE_FACTOR && frameMicPower > MIN_SIGNAL_POWER) {
            divergeStreak++
            if (divergeStreak >= DIVERGE_SLOTS) {
                weights.fill(0.0)
                delayConfident = false
                divergedFlag = true
                divergeStreak = 0
            }
        } else {
            divergeStreak = 0
        }

        // 6) Residual suppression gate (NLP-lite). While the far-end is
        //    audible, the error power is compared against a MIN-TRACKED
        //    residual floor (the level the converged filter leaves behind on
        //    echo alone). Error far ABOVE that floor means near-end speech is
        //    present → the gate opens (protects double-talk); error AT the
        //    floor means pure residual echo → the gate closes. Comparing
        //    error to PRE-cancel mic power (the naive ratio) would close the
        //    gate whenever the cancelled echo was louder than the near-end —
        //    exactly the double-talk case that must NOT be suppressed.
        updateResidFloor(frameErrPower, farActive)
        lastFrameErrPower = frameErrPower
        val gate: Float = if (farActive || farEndSilentSlots < RELEASE_SLOTS) {
            val target = if (residFloor < Double.MAX_VALUE && residFloor > MIN_SIGNAL_POWER) {
                (frameErrPower / (residFloor * GATE_OPEN_FACTOR)).coerceIn(MIN_GATE, 1.0)
            } else 1.0
            smoothGate(target)
        } else {
            smoothGate(1.0)
        }
        for (i in 0 until frameSamples) {
            val v = out[i] * gate
            out[i] = v
        }
        micPowerSum += frameMicPower
        errorPowerSum += frameErrPower
        adaptingFlag = farActive

        val shorts = ShortArray(frameSamples)
        for (i in 0 until frameSamples) {
            val v = (out[i] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            shorts[i] = v.toShort()
        }
        return shorts
    }

    // ------------------------------------------------------------------
    // ring bookkeeping
    // ------------------------------------------------------------------

    /** Ring index of far-end sample for mic position [iInFrame] under [delay]. */
    private fun ringIndex(iInFrame: Int, delay: Int): Int {
        // Far-end grid is written one slot per frame; the far-end samples for
        // this frame live at ring positions (end - frameSamples + i). The echo
        // of frame i arrived [delay] samples EARLIER on the far-end grid.
        val pos = ringWrite - frameSamples + iInFrame - delay
        return normalize(pos)
    }

    private fun nextRingIndex(i: Int): Int = normalize(i - 1)

    private fun normalize(pos: Int): Int = pos and RING_MASK

    private fun pushRing(v: Float) {
        ring[ringWrite and RING_MASK] = v
        ringWrite++
        if (ringFilled < RING_SAMPLES) ringFilled++
    }

    /** Input signal power over the current delayed window (for normalization). */
    private fun ringInputPower(delay: Int): Double {
        var acc = 0.0
        val start = ringIndex(0, delay)
        var idx = start
        var k = 0
        while (k < taps) {
            val v = ring[idx].toDouble()
            acc += v * v
            idx = nextRingIndex(idx)
            k++
        }
        return acc
    }

    private fun maybeReestimateDelay(micFrame: ShortArray) {
        if (ringFilled < MIN_HISTORY_FOR_ESTIMATE) return
        // Compare recent mic (1 frame, de-meaned, float int16-scale) against
        // far-end history: last (search + frame) ring samples.
        val mic = DelayAligner.toDemeanedFloats(micFrame)
        val micNorm = FloatArray(mic.size) { mic[it] * 32768f }
        val history = FloatArray(SEARCH_LAG + frameSamples)
        for (j in history.indices) {
            history[j] = ring[normalize(ringWrite - history.size + j)] * 32768f
        }
        val found = DelayAligner.estimate(micNorm, history, SEARCH_LAG)
        if (found != null) {
            val newDelay = found
            if (!delayConfident || kotlin.math.abs(newDelay - delaySamples) > REALIGN_RESET_STRIDE) {
                if (delayConfident) weights.fill(0.0) // re-align: re-converge
                delaySamples = newDelay
                delayConfident = true
            }
        }
    }

    /**
     * Min-track the residual floor:
     *  - new minimum wins instantly (echo got MORE cancellable);
     *  - error just above the floor (echo-only residual drift) rises slowly;
     *  - error FAR above the floor is double-talk — the floor is FROZEN. A
     *    rising floor would chase the near-end speech and the suppression
     *    gate would close on it (the failure mode found by the offline
     *    double-talk test: gate slid from 1.0 to 0.5 over a 2.4 s burst).
     *    Recovery from a genuine far-end PATH change is handled by
     *    [FREEZE_RESEED_SLOTS] instead of a drift rate.
     * Updated only while the far-end is audible — during silence the error
     * IS near-end speech and would poison the floor.
     */
    private fun updateResidFloor(frameErrPower: Double, farActive: Boolean) {
        if (!farActive) return
        if (frameErrPower < residFloor) {
            residFloor = frameErrPower
        } else if (frameErrPower <= residFloor * DTD_FACTOR) {
            residFloor += (frameErrPower - residFloor) * FLOOR_RISE_ALPHA
        }
        // else: double-talk — leave the floor untouched.
    }

    private fun smoothGate(target: Double): Float {
        gateSmooth += (target - gateSmooth) * GATE_ALPHA
        return gateSmooth.toFloat().coerceIn(MIN_GATE.toFloat(), 1f)
    }

    companion object {
        const val frameSamples = 320 // 20 ms @ 16 kHz

        /** 512 ms far-end history, power-of-two. */
        private const val RING_SAMPLES = 8192
        private const val RING_MASK = RING_SAMPLES - 1

        private const val ESTIMATE_INTERVAL_SLOTS = 12 // ~250 ms
        private const val BYPASS_SLOTS = 10            // 200 ms of far-end silence
        private const val RELEASE_SLOTS = 12           // 240 ms gate release
        private const val BYPASS_SILENCE_MS = 200L

        /** Delay search window (samples, 250 ms @ 16 kHz). */
        private const val SEARCH_LAG = 4000
        private const val MIN_HISTORY_FOR_ESTIMATE = SEARCH_LAG + frameSamples

        private const val REG_EPS = 1e-6
        private const val MIN_INPUT_POWER = 1e-6
        private const val MIN_SIGNAL_POWER = 1e-4
        private const val DIVERGE_FACTOR = 2.5
        private const val DIVERGE_SLOTS = 25
        private const val REALIGN_RESET_STRIDE = 64 // 4 ms
        private const val MIN_GATE = 0.15
        private const val GATE_ALPHA = 0.35

        /** Error this many × the residual floor opens the gate fully (~14 dB). */
        private const val GATE_OPEN_FACTOR = 25.0

        /** Residual-floor rise rate per frame while far-end is active. */
        private const val FLOOR_RISE_ALPHA = 0.005

        /** Error this many × the residual floor freezes adaptation (DTD). */
        private const val DTD_FACTOR = 10.0

        /** Frozen-adaptation streak that re-seeds the floor once (3 s of
         *  contiguous double-talk is unusual; a real path change recovers in
         *  ~3 s instead of never — see updateResidFloor). */
        private const val FREEZE_RESEED_SLOTS = 150

        const val LANE_DEFAULT = "far_end"
    }
}
