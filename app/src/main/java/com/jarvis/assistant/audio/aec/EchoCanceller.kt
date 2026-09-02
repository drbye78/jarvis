package com.jarvis.assistant.audio.aec

/**
 * Echo-cancellation contract for the mic lane.
 *
 * One instance owns the whole software-AEC state machine for one capture
 * pipeline. The producer loop calls [onFarEndFrame] whenever a far-end
 * reference chunk arrives (own TTS tap and/or playback capture, already
 * resampled to the mic rate) and [process] for every mic frame, IN ORDER.
 *
 * The built-in implementation is [NlmsEchoCanceller]; the interface is the
 * documented slot for a future native WebRTC-AEC3 drop-in (no Java-exposed
 * APM exists on Maven today — see PLAN-AEC-FOLLOWUP.md §0).
 */
interface EchoCanceller {

    /**
     * Feed one far-end reference frame (16 kHz mono PCM, [frame] is copied
     * internally; caller may reuse its buffer).
     * @param atNanos monotonic timestamp of when this audio is (or will be)
     *        audible at the speaker, nanoseconds.
     */
    fun onFarEndFrame(frame: ShortArray, atNanos: Long = 0L)

    /**
     * Cancel echo in one mic frame (16 kHz mono, 320 samples = 20 ms).
     * Returns the processed frame; may be the SAME array instance when AEC
     * is bypassed (far-end silent) — callers must not retain it without
     * copying (AudioPipeline already copies once).
     */
    fun process(micFrame: ShortArray): ShortArray

    /** Drop all adaptive state (mode switch, restart, divergence). */
    fun reset()

    /** Diagnostics snapshot for AecDiag logging / tests. */
    val stats: Stats

    data class Stats(
        /** Estimated bulk delay far-end→mic in ms, or null when unknown. */
        val estimatedDelayMs: Long?,
        /** Echo-return-loss enhancement estimate over the far-end-active span. */
        val erleDb: Double?,
        /** True while the canceller is adapting (far-end energy present). */
        val adapting: Boolean,
        /** True when the divergence guard fired and the filter was reset. */
        val diverged: Boolean,
        /** Current residual-suppression gate gain (1.0 = fully open). */
        val gateGain: Float,
        /** frameErrPower / residFloor — ≈1 means converged echo-only; ≫10 means double-talk. */
        val errorToFloor: Double?,
    )
}
