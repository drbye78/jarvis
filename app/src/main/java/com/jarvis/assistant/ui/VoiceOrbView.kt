package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.jarvis.assistant.R
import com.jarvis.assistant.model.AssistantState
import kotlin.math.abs
import kotlin.math.min

/**
 * The voice-status orb — the home screen's live indicator of what the
 * assistant is doing right now. Pure Canvas drawing (no drawables, no
 * extra dependencies), driven by cheap animators:
 *
 *  IDLE            — a dim ring that slowly "breathes" (alpha oscillation)
 *  LISTENING       — two offset ripples expanding from the core
 *  THINKING        — three rounded arc segments rotating around the ring
 *  SPEAKING        — a pulsing glow around a bright core
 *  FOLLOW_UP       — listening-tinted ripples + a shrinking countdown arc
 *                    (remaining window time, driven by setFollowUpProgress)
 *  MUTED           — flat, gray, motionless (microphone is off)
 *  DEAF            — static error-tinted ring (wake-word engine failed:
 *                    no detection is possible, never reads as "listening")
 *
 * Contract: [setState] may be called from anywhere (it only mutates fields
 * and restarts animators on the UI thread — callers are the activity's
 * state collectors, which are already main-dispatched). Animators are
 * cancelled in [onDetachedFromWindow], so a destroyed screen leaks nothing.
 */
class VoiceOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** Displayed state; derived from [AssistantState] + mute/deaf flags. */
    private var state = OrbState.IDLE

    /** Remaining follow-up window fraction (0..1); drives the countdown arc. */
    private var followUpProgress = 0f

    /**
     * Mic loudness, 0..1, published by [setLevel] (see [AudioLevel]). Only
     * consulted while LISTENING or in the follow-up window — the two states
     * where the mic is actually open.
     */
    private var level = 0f

    /**
     * Wake-cue progress, 0..1 (1 = settled, the normal LISTENING look). Driven
     * by [playWakeCue]; the drawn envelope is [listeningWakeOvershoot], which is
     * ZERO at both ends, so a cue always completes cleanly and can never leave
     * the orb frozen part-way.
     */
    private var wakeCue = 1f

    // Animator-driven phases (all 0f..1f or degrees).
    private var breathePhase = 0f
    private var ripplePhase = 0f
    private var rotationDegrees = 0f
    private var pulsePhase = 0f

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var idleColor = 0
    private var listeningColor = 0
    private var thinkingColor = 0
    private var speakingColor = 0
    private var mutedColor = 0
    private var deafColor = 0

    private var breatheAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    /**
     * One-shot wake acknowledgement (see [playWakeCue]). Kept OUT of
     * [stopAnimators]: a state change must not cancel it mid-flight — a wake
     * fires essentially together with the IDLE -> LISTENING transition, and
     * cancelling on that very transition is exactly how the cue would never
     * get to play. It is stopped only on detach and on a reduced-motion flip.
     */
    private var wakeAnimator: ValueAnimator? = null

    /**
     * Re-runs the animator decision when the system reduced-motion setting
     * flips while this orb is attached (accessibility toggle). Registered in
     * [onAttachedToWindow], removed in [onDetachedFromWindow] so a destroyed
     * view is never retained by the [Motion] observer.
     */
    private val motionListener: () -> Unit = {
        restartAnimators()
        invalidate()
    }

    init {
        resolveColors()
    }

    private fun resolveColors() {
        idleColor = ContextCompat.getColor(context, R.color.jarvis_status_idle)
        listeningColor = ContextCompat.getColor(context, R.color.jarvis_status_listening)
        thinkingColor = ContextCompat.getColor(context, R.color.jarvis_status_thinking)
        speakingColor = ContextCompat.getColor(context, R.color.jarvis_status_speaking)
        mutedColor = ContextCompat.getColor(context, R.color.jarvis_status_idle)
        deafColor = ContextCompat.getColor(context, R.color.jarvis_error)
    }

    /**
     * Map a session-machine state + mute flag onto the orb's visual state.
     * [deaf] (wake-word engine failed — nothing can hear) takes precedence:
     * the orb must never look like it is listening when it cannot hear.
     */
    fun setState(state: AssistantState?, muted: Boolean, deaf: Boolean = false) {
        val next = OrbShape.of(state, muted, deaf)
        if (next == this.state) return
        this.state = next
        // Leaving a capture state discards the loudness so re-entering LISTENING
        // (e.g. the follow-up window) starts from a calm orb instead of the
        // tail of the previous utterance's level.
        if (next != OrbState.LISTENING && next != OrbState.FOLLOW_UP) level = 0f
        restartAnimators()
        invalidate()
    }

    /**
     * Mic loudness, 0..1 (see [AudioLevel]). Drives the loudness glow and core
     * while LISTENING or in the follow-up window.
     *
     * Two cost guards, because this is fed ~50 frames/s on an always-on,
     * low-end device: reduced motion pins the value to 0 (a voice-driven pulse
     * is movement, and the orb must stay the calm static listening look), and a
     * change smaller than [LEVEL_VISIBLE_EPSILON] is dropped — it cannot be
     * seen, so it must not cost a redraw. The caller additionally throttles
     * posts to display rate.
     */
    fun setLevel(level: Float) {
        val next = if (Motion.animationsEnabled()) level.coerceIn(0f, 1f) else 0f
        if (abs(next - this.level) < LEVEL_VISIBLE_EPSILON) return
        this.level = next
        if (state == OrbState.LISTENING || state == OrbState.FOLLOW_UP) invalidate()
    }

    /**
     * Wake word heard: a brief expand + colour flash that lands back into the
     * plain LISTENING look. The envelope ([listeningWakeOvershoot]) returns to
     * zero by itself, so this is safe to call from any state, while muted, or
     * repeatedly — it can never strand the orb mid-animation.
     *
     * Reduced motion: no cue at all. The orb simply shows the static listening
     * frame, which is the one honest end state.
     */
    fun playWakeCue() {
        wakeAnimator?.cancel()
        wakeAnimator = null
        if (!Motion.animationsEnabled()) {
            wakeCue = 1f
            return
        }
        wakeCue = 0f
        wakeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = WAKE_CUE_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                wakeCue = animator.animatedValue as Float
                if (state == OrbState.LISTENING || state == OrbState.FOLLOW_UP) {
                    postInvalidateOnAnimation()
                }
            }
            start()
        }
        invalidate()
    }

    /** End any in-flight wake cue and pin it settled (detach / reduced motion). */
    private fun stopWakeCue() {
        wakeAnimator?.cancel()
        wakeAnimator = null
        wakeCue = 1f
    }

    /** Update the follow-up countdown arc (call from the progress collector). */
    fun setFollowUpProgress(fraction: Float) {
        followUpProgress = fraction.coerceIn(0f, 1f)
        if (state == OrbState.FOLLOW_UP) invalidate()
    }

    // ------------------------------------------------------------------
    // Animators — one per visual effect, started only for the active state
    // ------------------------------------------------------------------

    private fun restartAnimators() {
        stopAnimators()
        if (!Motion.animationsEnabled()) {
            // A reduced-motion flip can land mid-cue: end the cue and drop the
            // loudness rather than leaving a half-expanded, half-bright orb.
            stopWakeCue()
            level = 0f
            applyStaticFrame()
            return
        }
        when (state) {
            OrbState.IDLE -> breatheAnimator = floatAnimator(3_000L) { breathePhase = it }
            OrbState.LISTENING -> rippleAnimator = floatAnimator(2_400L) { ripplePhase = it }
            OrbState.THINKING -> rotationAnimator = degreeAnimator(1_800L) { rotationDegrees = it }
            OrbState.SPEAKING -> pulseAnimator = floatAnimator(1_400L) { pulsePhase = it }
            // Same ripple energy as LISTENING — the countdown arc (drawn per
            // progress update, not per animator tick) tells them apart.
            OrbState.FOLLOW_UP -> rippleAnimator = floatAnimator(2_400L) { ripplePhase = it }
            OrbState.MUTED -> Unit // motionless by design
            OrbState.DEAF -> Unit // static by design — nothing "live" to show
        }
    }

    /**
     * Reduced motion: pin each state's phase to a still, representative frame
     * instead of running an animator, then redraw. The orb must never go blank
     * or stop conveying the state — IDLE, LISTENING, THINKING and SPEAKING are
     * still tellable apart from the frozen frame alone.
     *
     * FOLLOW_UP is special: its countdown arc is drawn from [followUpProgress]
     * in the draw path, not from the ripple phase, so it keeps rendering at its
     * accurate value here. It is information, not decoration — only the
     * companion ripple freezes.
     */
    private fun applyStaticFrame() {
        when (state) {
            OrbState.IDLE -> breathePhase = 0.5f
            OrbState.LISTENING -> ripplePhase = 0.25f
            OrbState.THINKING -> rotationDegrees = 0f
            OrbState.SPEAKING -> pulsePhase = 0.5f
            OrbState.FOLLOW_UP -> ripplePhase = 0.25f
            OrbState.MUTED -> Unit // already motionless
            OrbState.DEAF -> Unit // already static
        }
        invalidate()
    }

    private fun stopAnimators() {
        breatheAnimator?.cancel()
        breatheAnimator = null
        rippleAnimator?.cancel()
        rippleAnimator = null
        rotationAnimator?.cancel()
        rotationAnimator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    private inline fun floatAnimator(durationMs: Long, crossinline update: (Float) -> Unit) =
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                update(animator.animatedValue as Float)
                postInvalidateOnAnimation()
            }
            start()
        }

    private inline fun degreeAnimator(durationMs: Long, crossinline update: (Float) -> Unit) =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = durationMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                update(animator.animatedValue as Float)
                postInvalidateOnAnimation()
            }
            start()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Reduced motion can flip while this orb is on screen (the user
        // toggles "Remove animations" in accessibility settings); the listener
        // re-runs the animator decision so the change takes effect live.
        Motion.addListener(motionListener)
        // Animators were cancelled in onDetachedFromWindow; a re-attached orb
        // (config change, back navigation) would otherwise sit frozen until
        // the next setState. This also starts IDLE breathing on first attach.
        restartAnimators()
    }

    override fun onDetachedFromWindow() {
        // Unregister before cancelling: a destroyed orb must not be retained
        // by the Motion observer.
        Motion.removeListener(motionListener)
        stopAnimators()
        stopWakeCue()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy)
        // The wake cue expands the WHOLE orb — core, ring and the ripples
        // derived from them — so it blooms and settles back into the plain
        // LISTENING look. Scaled here rather than inside drawListening so the
        // follow-up window's countdown arc grows with it too.
        val wakeScale = 1f + WAKE_EXPAND * listeningWakeOvershoot()
        val core = radius * 0.16f * wakeScale
        val ring = radius * 0.46f * wakeScale
        val stroke = radius * 0.045f
        ringPaint.strokeWidth = stroke
        ringPaint.strokeCap = Paint.Cap.ROUND

        when (state) {
            OrbState.IDLE -> drawIdle(canvas, cx, cy, core, ring)
            OrbState.LISTENING -> drawListening(canvas, cx, cy, core, ring)
            OrbState.THINKING -> drawThinking(canvas, cx, cy, core, ring)
            OrbState.SPEAKING -> drawSpeaking(canvas, cx, cy, core, ring)
            OrbState.FOLLOW_UP -> drawFollowUp(canvas, cx, cy, core, ring)
            OrbState.MUTED -> drawMuted(canvas, cx, cy, core, ring)
            OrbState.DEAF -> drawDeaf(canvas, cx, cy, core, ring)
        }
    }

    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        val alpha = 0.35f + 0.25f * breathePhase
        val coreColor = withAlpha(idleColor, (alpha + 0.2f).coerceAtMost(1f))
        val ringColor = withAlpha(idleColor, alpha)
        corePaint.color = coreColor
        canvas.drawCircle(cx, cy, core, corePaint)
        ringPaint.color = ringColor
        canvas.drawCircle(cx, cy, ring, ringPaint)
    }

    private fun drawListening(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        val cue = listeningWakeOvershoot()
        // Loudness/wake layer first (glow behind, then core) so the ring and
        // ripples read on top of it.
        drawLoudness(canvas, cx, cy, core, ring, listeningCoreTint())
        ringPaint.color = withAlpha(listeningColor, 0.7f + 0.25f * cue)
        canvas.drawCircle(cx, cy, ring, ringPaint)

        for (i in 0..1) {
            val phase = (ripplePhase + i * 0.5f) % 1f
            val rippleRadius = core + (ring - core) * phase + ring * 0.25f * phase
            val alpha = (1f - phase) * (0.35f + 0.2f * cue)
            if (alpha > 0.01f) {
                ringPaint.color = withAlpha(listeningColor, alpha)
                canvas.drawCircle(cx, cy, rippleRadius, ringPaint)
            }
        }
    }

    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        corePaint.color = withAlpha(thinkingColor, 0.6f)
        canvas.drawCircle(cx, cy, core, corePaint)
        ringPaint.color = thinkingColor
        // Three rounded arc segments rotating — the classic "working" affordance.
        for (i in 0..2) {
            val start = rotationDegrees + i * 120f
            canvas.drawArc(
                cx - ring,
                cy - ring,
                cx + ring,
                cy + ring,
                start,
                55f,
                false,
                ringPaint,
            )
        }
    }

    private fun drawSpeaking(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        val scale = 1f + 0.22f * pulsePhase
        // Soft glow behind the core: a large translucent fill growing with the pulse.
        glowPaint.color = withAlpha(speakingColor, 0.18f * pulsePhase + 0.08f)
        canvas.drawCircle(cx, cy, ring * 1.35f * scale, glowPaint)
        corePaint.color = speakingColor
        canvas.drawCircle(cx, cy, core * (1f + 0.35f * pulsePhase), corePaint)
        ringPaint.color = withAlpha(speakingColor, 0.8f)
        canvas.drawCircle(cx, cy, ring * scale, ringPaint)
    }

    /**
     * Follow-up window: LISTENING's ripples (the mic IS open) plus a countdown
     * arc that sweeps clockwise from 12 o'clock and shrinks as the window
     * runs out — the "keep talking" affordance, drawn in the speaking accent
     * so it reads as "the assistant just spoke, mic re-opened".
     */
    private fun drawFollowUp(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        val cue = listeningWakeOvershoot()
        // Ripple base, exactly like LISTENING (plus the loudness layer — the
        // mic is open in this window too).
        drawLoudness(canvas, cx, cy, core, ring, listeningCoreTint())
        ringPaint.color = withAlpha(listeningColor, 0.35f + 0.25f * cue)
        canvas.drawCircle(cx, cy, ring, ringPaint)
        for (i in 0..1) {
            val phase = (ripplePhase + i * 0.5f) % 1f
            val rippleRadius = core + (ring - core) * phase + ring * 0.25f * phase
            val alpha = (1f - phase) * (0.3f + 0.2f * cue)
            if (alpha > 0.01f) {
                ringPaint.color = withAlpha(listeningColor, alpha)
                canvas.drawCircle(cx, cy, rippleRadius, ringPaint)
            }
        }
        // Countdown arc: sweep = remaining fraction of the window, floored at
        // the shape-channel minimum so FOLLOW_UP never becomes shapeless.
        val sweep = (360f * followUpProgress)
            .coerceAtLeast(OrbShape.MIN_FOLLOW_UP_ARC_DEGREES)
        ringPaint.color = withAlpha(speakingColor, 0.9f)
        canvas.drawArc(
            cx - ring,
            cy - ring,
            cx + ring,
            cy + ring,
            -90f,
            sweep,
            false,
            ringPaint,
        )
    }

    private fun drawMuted(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        corePaint.color = withAlpha(mutedColor, 0.4f)
        canvas.drawCircle(cx, cy, core, corePaint)
        ringPaint.color = withAlpha(mutedColor, 0.3f)
        canvas.drawCircle(cx, cy, ring, ringPaint)
        // Shape channel (U12): a diagonal slash through the ring. Colour alone
        // cannot carry "microphone off" for a colour-blind user, and MUTED
        // must not read as a merely dim IDLE ring.
        ringPaint.color = withAlpha(mutedColor, 0.55f)
        val slash = ring * 0.72f
        canvas.drawLine(cx - slash, cy + slash, cx + slash, cy - slash, ringPaint)
    }

    /**
     * Deaf wake-word engine: a static error-tinted ring — no ripple, no
     * breathing, nothing that reads as "listening" (the mic cannot hear).
     * Distinct from MUTED (a user intent) and IDLE (waiting, alive).
     */
    private fun drawDeaf(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        corePaint.color = withAlpha(deafColor, 0.5f)
        canvas.drawCircle(cx, cy, core, corePaint)
        ringPaint.color = withAlpha(deafColor, 0.6f)
        canvas.drawCircle(cx, cy, ring, ringPaint)
        // Shape channel (U12): a radial tick at 12 o'clock. DEAF is the most
        // important shape to tell apart from MUTED — both are "not hearing",
        // with opposite causes (engine failure vs user intent) — so it must
        // not rely on the error hue.
        ringPaint.color = withAlpha(deafColor, 0.9f)
        canvas.drawLine(cx, cy - ring * 1.14f, cx, cy - ring * 0.84f, ringPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

    /**
     * The mic-reactive layer shared by LISTENING and FOLLOW_UP: a soft glow
     * whose radius and alpha grow with the loudness [level], plus a core that
     * swells with it. During the wake cue the cue's overshoot drives the same
     * layer, so the orb lights up on the acknowledgement even before the first
     * syllable has been smoothed in.
     */
    private fun drawLoudness(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float, color: Int) {
        val cue = listeningWakeOvershoot()
        val intensity = if (cue > level) cue else level
        if (intensity > LEVEL_VISIBLE_EPSILON) {
            glowPaint.color = withAlpha(color, 0.10f + 0.30f * intensity)
            canvas.drawCircle(cx, cy, ring * (1.15f + 0.55f * intensity), glowPaint)
        }
        corePaint.color = color
        canvas.drawCircle(cx, cy, core * (1f + 0.5f * level), corePaint)
    }

    /**
     * The colour the LISTENING/FOLLOW_UP core and glow draw with: the plain
     * listening accent, flashed lighter by the wake cue.
     *
     * The blend target is white because the palette has no role that is
     * "brighter than listening" in BOTH themes — `primary_container` lightens in
     * the day theme but darkens in night, and vice versa for the other
     * containers. Blending toward white only ever lightens, so the same code
     * reads as a flash of life in either theme.
     */
    private fun listeningCoreTint(): Int {
        val cue = listeningWakeOvershoot()
        return if (cue <= 0f) {
            listeningColor
        } else {
            ColorUtils.blendARGB(listeningColor, Color.WHITE, WAKE_HIGHLIGHT * cue)
        }
    }

    /**
     * Wake-cue envelope: 0 at both ends, 1 at the midpoint, so the orb expands
     * and brightens then SETTLES BACK into the unmodified LISTENING look — the
     * cue can never leave it stuck mid-animation. Zero when no cue is running
     * or the orb is not in a capture state, so an idle/thinking/speaking orb is
     * untouched by a cue that arrived at the wrong moment.
     */
    private fun listeningWakeOvershoot(): Float {
        if (state != OrbState.LISTENING && state != OrbState.FOLLOW_UP) return 0f
        if (wakeCue <= 0f || wakeCue >= 1f) return 0f
        return 4f * wakeCue * (1f - wakeCue)
    }

    private companion object {
        /**
         * Smallest loudness/wake change worth a redraw. 0.01 of a 168 dp orb is
         * far under a pixel, so anything smaller would burn a frame for no
         * visible change.
         */
        const val LEVEL_VISIBLE_EPSILON = 0.01f

        /** Wake cue length: a brief acknowledgement, not a set-piece. */
        const val WAKE_CUE_MS = 560L

        /** Peak extra scale at the middle of the wake cue (~8 dp on the home orb). */
        const val WAKE_EXPAND = 0.2f

        /** How far the wake cue flashes the core toward white, at its peak. */
        const val WAKE_HIGHLIGHT = 0.45f
    }
}
