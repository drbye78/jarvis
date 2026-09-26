package com.jarvis.assistant.ui

import com.jarvis.assistant.model.AssistantState
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Microphone loudness -> orb response maths (home-orb loudness lane).
 *
 * Pure Kotlin on purpose: this is the part that decides whether the orb
 * "resembles speech" rather than noise, and it is the only part of the feature
 * that can be pinned without a device. [VoiceOrbView] only draws the result and
 * `MainActivity` only pumps frames through [AudioLevelMeter].
 *
 * Two decisions do the heavy lifting:
 *
 *  1. PERCEPTUAL MAPPING. Raw RMS is a linear amplitude and speech loudness is
 *     roughly logarithmic, so a linear map either pins the orb at full scale for
 *     ordinary speech or leaves it dead. [normalize] converts to dBFS and
 *     normalises over [NOISE_FLOOR_DB]..[CEILING_DB]. The floor doubles as the
 *     silence gate, so a quiet room reads as a genuine 0 and the orb is calm
 *     when nobody is talking.
 *
 *  2. ATTACK/RELEASE. Per-frame RMS at 50 fps is jittery — syllables, plosives
 *     and the odd dropped frame all move it. [smooth] is a one-pole filter with
 *     a FAST attack and a SLOW release, the standard VU/limiter envelope: the
 *     orb jumps on a syllable onset and decays gracefully, instead of strobing
 *     between words.
 */
object AudioLevel {

    /**
     * Silence gate in dBFS; below this the level is 0. Set above the self-noise
     * of a tablet's built-in mic in a quiet room (~-60..-50 dBFS after AEC), so
     * ambient hiss cannot keep the orb glowing.
     */
    const val NOISE_FLOOR_DB = -55f

    /**
     * Full-scale point in dBFS. Normal-to-loud speech at arm's length on the
     * target tablet peaks around -20..-12 dBFS, so this maps that range to the
     * top of the scale while leaving headroom before clipping.
     */
    const val CEILING_DB = -12f

    /**
     * Attack time constant in ms. Fast enough to catch a syllable onset (a
     * stressed syllable rises over ~40-80 ms) without following 20 ms frame
     * noise.
     */
    const val ATTACK_MS = 40f

    /**
     * Release time constant in ms, ~5x the attack. It bridges the gaps BETWEEN
     * syllables and words (tens to a couple of hundred ms) so the orb settles
     * smoothly instead of flickering off mid-sentence.
     */
    const val RELEASE_MS = 220f

    /**
     * Root-mean-square amplitude of one PCM frame, normalised to 0..1.
     * Allocation-free: the capture loop runs this on every 20 ms frame while
     * listening, so a per-frame allocation would be real GC pressure.
     */
    fun rms(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (sample in frame) {
            val v = sample.toDouble()
            sum += v * v
        }
        return (sqrt(sum / frame.size) / 32768.0).toFloat()
    }

    /**
     * Map a linear RMS to a perceptual 0..1 level. Digital silence and anything
     * under [NOISE_FLOOR_DB] collapse to 0; anything at or above [CEILING_DB]
     * saturates to 1.
     */
    fun normalize(rms: Float): Float {
        if (rms <= 0f) return 0f
        val db = 20f * log10(rms)
        if (db <= NOISE_FLOOR_DB) return 0f
        if (db >= CEILING_DB) return 1f
        return (db - NOISE_FLOOR_DB) / (CEILING_DB - NOISE_FLOOR_DB)
    }

    /**
     * One-pole attack/release smoothing step. [dtMs] is the frame duration and
     * the coefficient is `1 - e^(-dt/tau)`, which keeps the response
     * frame-rate independent: a dropped frame simply moves further, it does not
     * change the time constant.
     */
    fun smooth(
        previous: Float,
        target: Float,
        dtMs: Float,
        attackMs: Float = ATTACK_MS,
        releaseMs: Float = RELEASE_MS,
    ): Float {
        val tau = if (target > previous) attackMs else releaseMs
        if (tau <= 0f) return target.coerceIn(0f, 1f)
        val alpha = 1f - exp(-dtMs / tau)
        return (previous + (target - previous) * alpha).coerceIn(0f, 1f)
    }

    /**
     * True while the assistant is actually capturing: LISTENING or the
     * follow-up window (the mic is open in both). The orb's loudness meter
     * subscribes to the mic frames ONLY for these states, so an idle or
     * speaking assistant does zero per-frame work on an always-on device.
     */
    fun capturesAudio(state: AssistantState?): Boolean =
        state == AssistantState.LISTENING || state == AssistantState.FOLLOW_UP_WINDOW
}

/**
 * Stateful wrapper: folds frames through [AudioLevel] and holds the smoothed
 * level. Deliberately not a View, so it stays JVM-testable. `MainActivity`
 * owns one per capture session and posts [level] to the orb at display rate.
 */
class AudioLevelMeter {

    /** Current smoothed level, 0..1. */
    var level = 0f
        private set

    /** Fold one PCM frame in and return the updated level. */
    fun onFrame(frame: ShortArray, dtMs: Float): Float {
        level = AudioLevel.smooth(level, AudioLevel.normalize(AudioLevel.rms(frame)), dtMs)
        return level
    }

    /** Drop back to silence (a new capture session, or the app backgrounded). */
    fun reset() {
        level = 0f
    }
}
