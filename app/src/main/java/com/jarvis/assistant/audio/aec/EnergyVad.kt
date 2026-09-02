package com.jarvis.assistant.audio.aec

/**
 * Energy-based voice activity detector for the follow-up window lane.
 *
 * Time-domain, zero dependencies: short-term RMS against an adaptive noise
 * floor with onset/offset hysteresis and hangover. It answers ONE question
 * — "did the user start speaking?" — for opening the follow-up turn; EOU
 * (end of utterance) remains the ASR server's job.
 *
 * Speech onset detection uses an energy RATIO (short-term RMS ÷ slow noise
 * floor) rather than an absolute threshold, so loud environments adapt; a
 * steady music bed raises the floor and is *not* an onset by itself (level
 * music has low ratio). This is deliberately NOT music-proof — see RUNBOOK
 * (follow-up window + external music) for the honest interplay with
 * pauseMusicOnWake / AEC.
 *
 * Pure Kotlin; feed 20 ms @ 16 kHz frames (320 samples) in order.
 */
class EnergyVad(
    /** Onset ratio: short RMS must exceed the floor by this factor. */
    private val onsetRatio: Double = 6.0,
    /** Offset ratio: speech ends when RMS falls below floor × this. */
    private val offsetRatio: Double = 2.5,
    /** Consecutive onset frames before "active" fires (40 ms at 320/frame). */
    private val onsetFrames: Int = 2,
    /** Consecutive offset frames before "inactive" fires (160 ms). */
    private val offsetFrames: Int = 8,
    /** Hangover: keep reporting active this many frames after offset. */
    private val hangoverFrames: Int = 12,
    /** Initial noise-floor RMS (int16 scale); adapts within seconds. */
    initialFloor: Double = 200.0,
) {
    enum class State { SILENT, ACTIVE }

    var state: State = State.SILENT
        private set

    /** Current adapted noise floor (RMS, int16 scale). */
    var noiseFloor: Double = initialFloor
        private set

    /** Last frame's RMS (int16 scale). */
    var lastRms: Double = 0.0
        private set

    private var onsetStreak = 0
    private var offsetStreak = 0
    private var hangover = 0
    private var onsetLatch = false

    /**
     * Process one frame; returns the (possibly changed) activity state.
     * [onset] is true on exactly the frame where SILENT→ACTIVE fires
     * (rising edge, one per speech burst).
     */
    fun process(frame: ShortArray): State {
        onsetLatch = false
        val rms = rmsOf(frame)
        lastRms = rms

        // Noise floor: fast down, slow up (frozen while speech is active).
        val inSpeech = state == State.ACTIVE
        if (rms < noiseFloor) {
            noiseFloor = rms
        } else if (!inSpeech) {
            noiseFloor += (rms - noiseFloor) / FLOOR_ATTACK_SLOTS
        }
        val floor = noiseFloor.coerceAtLeast(MIN_FLOOR)

        when (state) {
            State.SILENT -> {
                if (rms > floor * onsetRatio) {
                    onsetStreak++
                    if (onsetStreak >= onsetFrames) {
                        state = State.ACTIVE
                        onsetLatch = true
                        hangover = hangoverFrames
                        onsetStreak = 0
                        offsetStreak = 0
                    }
                } else {
                    onsetStreak = 0
                }
            }
            State.ACTIVE -> {
                if (rms < floor * offsetRatio) {
                    offsetStreak++
                    if (offsetStreak >= offsetFrames || hangover <= 0) {
                        state = State.SILENT
                        offsetStreak = 0
                    }
                } else {
                    offsetStreak = 0
                    hangover = hangoverFrames // still speaking: refresh
                }
                hangover--
            }
        }
        return state
    }

    /** Rising-edge flag, valid for the frame just processed by [process]. */
    val onset: Boolean
        get() = onsetLatch

    /** Drop all adaptation (noise floor, streaks, hangover). */
    fun reset() {
        state = State.SILENT
        onsetStreak = 0
        offsetStreak = 0
        hangover = 0
        onsetLatch = false
        lastRms = 0.0
        // noiseFloor deliberately NOT reset: the room did not get quieter
        // because a new window opened.
    }

    /**
     * Force SILENT without touching the adapted noise floor — used at the end
     * of a lead-in that swallowed an onset (e.g. speech already in progress
     * when the window opened). Continuous speech re-fires [onset] within two
     * frames against the KEPT floor; a decaying transient does not.
     */
    fun forceSilent() {
        state = State.SILENT
        onsetStreak = 0
        offsetStreak = 0
        hangover = 0
        onsetLatch = false
    }

    private fun rmsOf(frame: ShortArray): Double {
        if (frame.isEmpty()) return 0.0
        var acc = 0.0
        for (s in frame) acc += (s.toDouble() * s.toDouble())
        return Math.sqrt(acc / frame.size)
    }

    companion object {
        private const val FLOOR_ATTACK_SLOTS = 200.0 // ~4 s to track rising noise
        private const val MIN_FLOOR = 40.0            // int16 RMS quantum
    }
}
