package com.jarvis.assistant.session

/**
 * Level-relative arming gate for the follow-up window's VAD.
 *
 * The window opens when TTS **drains** — the player's buffer end, NOT when the
 * speaker goes acoustically quiet. With echo cancellation off the mic still
 * hears the assistant's own reply, so a fixed lead-in alone armed the VAD while
 * the last word was still ringing; two frames above the VAD onset ratio then
 * started a phantom follow-up turn from the assistant's own voice. On the
 * device those phantom turns fired ~233 ms after the window opened — exactly
 * `lead-in 200 ms + 2 onset frames`, i.e. the earliest instant the old code
 * allowed, which only happens when something was already loud at arming time.
 *
 * This gate arms only once the input has returned to the room's noise floor
 * for [armedFrames] consecutive frames, and never before [leadInFrames]. A
 * bounded [maxSettleFrames] fallback arms anyway so a room that stays loud
 * cannot leave the follow-up feature silently dead.
 *
 * Pure: no coroutines, no clock — the collector feeds RMS and the adapted
 * floor per frame. In a genuinely quiet room the gate opens exactly at the
 * lead-in boundary, so the normal (AEC-on) case gains no latency.
 *
 * Honest trade-off: a user who starts speaking WHILE the reply's tail is
 * still audible arms no sooner than the fallback (their own voice is not
 * "quiet"), so such an onset is dropped. That is a barge-in, not a follow-up —
 * the wake word still works, and running AEC off is what makes the tail
 * audible in the first place. Preferring silence over phantom turns is the
 * deliberate choice here.
 */
class FollowUpTailGate(
    private val leadInFrames: Int = LEAD_IN_FRAMES,
    private val armRatio: Double = ARM_RATIO,
    private val armedFrames: Int = ARMED_FRAMES,
    private val maxSettleFrames: Int = MAX_SETTLE_FRAMES,
) {
    /** True once the gate has opened; the caller then watches for onsets. */
    var armed: Boolean = false
        private set

    private var frames = 0
    private var quietStreak = 0

    /**
     * Feed one frame's RMS and the VAD's adapted noise floor. Returns true on
     * the single frame the gate opens (the caller drops the activity the tail
     * accumulated); false on every other frame.
     */
    fun onFrame(rms: Double, floor: Double): Boolean {
        if (armed) return false
        frames++
        if (rms <= floor * armRatio) {
            quietStreak++
        } else {
            quietStreak = 0
        }
        val settled = frames >= maxSettleFrames
        if (frames >= leadInFrames && (quietStreak >= armedFrames || settled)) {
            armed = true
            return true
        }
        return false
    }

    companion object {
        /** Frames whose onset is always ignored at window open (200 ms). */
        const val LEAD_IN_FRAMES = 10

        /** Below the VAD's onset ratio (6.0): the tail must decay to the floor. */
        const val ARM_RATIO = 3.0

        /** Consecutive quiet frames required — no mid-word notch arms the gate. */
        const val ARMED_FRAMES = 3

        /** Bounded fallback (800 ms) so a constantly-loud room is not deaf. */
        const val MAX_SETTLE_FRAMES = 40
    }
}
