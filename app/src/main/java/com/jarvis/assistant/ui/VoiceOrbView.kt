package com.jarvis.assistant.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.jarvis.assistant.R
import com.jarvis.assistant.model.AssistantState
import kotlin.math.min

/**
 * The voice-status orb — the home screen's live indicator of what the
 * assistant is doing right now. Pure Canvas drawing (no drawables, no
 * extra dependencies), driven by four cheap animators:
 *
 *  IDLE      — a dim ring that slowly "breathes" (alpha oscillation)
 *  LISTENING — two offset ripples expanding from the core
 *  THINKING  — three rounded arc segments rotating around the ring
 *  SPEAKING  — a pulsing glow around a bright core
 *  MUTED     — flat, gray, motionless (microphone is off)
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

    /** Displayed state; derived from [AssistantState] + the muted flag. */
    enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, MUTED }

    private var state = OrbState.IDLE

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

    private var breatheAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        resolveColors()
    }

    private fun resolveColors() {
        idleColor = ContextCompat.getColor(context, R.color.jarvis_status_idle)
        listeningColor = ContextCompat.getColor(context, R.color.jarvis_status_listening)
        thinkingColor = ContextCompat.getColor(context, R.color.jarvis_status_thinking)
        speakingColor = ContextCompat.getColor(context, R.color.jarvis_status_speaking)
        mutedColor = ContextCompat.getColor(context, R.color.jarvis_status_idle)
    }

    /** Map a session-machine state + mute flag onto the orb's visual state. */
    fun setState(state: AssistantState?, muted: Boolean) {
        val next = when {
            muted -> OrbState.MUTED
            state == null -> OrbState.IDLE
            else -> when (state) {
                AssistantState.LISTENING -> OrbState.LISTENING
                AssistantState.THINKING -> OrbState.THINKING
                AssistantState.SPEAKING -> OrbState.SPEAKING
                AssistantState.IDLE -> OrbState.IDLE
            }
        }
        if (next == this.state) return
        this.state = next
        restartAnimators()
        invalidate()
    }

    // ------------------------------------------------------------------
    // Animators — one per visual effect, started only for the active state
    // ------------------------------------------------------------------

    private fun restartAnimators() {
        stopAnimators()
        when (state) {
            OrbState.IDLE -> breatheAnimator = floatAnimator(3_000L) { breathePhase = it }
            OrbState.LISTENING -> rippleAnimator = floatAnimator(2_400L) { ripplePhase = it }
            OrbState.THINKING -> rotationAnimator = degreeAnimator(1_800L) { rotationDegrees = it }
            OrbState.SPEAKING -> pulseAnimator = floatAnimator(1_400L) { pulsePhase = it }
            OrbState.MUTED -> Unit // motionless by design
        }
    }

    private fun stopAnimators() {
        breatheAnimator?.cancel(); breatheAnimator = null
        rippleAnimator?.cancel(); rippleAnimator = null
        rotationAnimator?.cancel(); rotationAnimator = null
        pulseAnimator?.cancel(); pulseAnimator = null
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

    override fun onDetachedFromWindow() {
        stopAnimators()
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
        val core = radius * 0.16f
        val ring = radius * 0.46f
        val stroke = radius * 0.045f
        ringPaint.strokeWidth = stroke
        ringPaint.strokeCap = Paint.Cap.ROUND

        when (state) {
            OrbState.IDLE -> drawIdle(canvas, cx, cy, core, ring)
            OrbState.LISTENING -> drawListening(canvas, cx, cy, core, ring)
            OrbState.THINKING -> drawThinking(canvas, cx, cy, core, ring)
            OrbState.SPEAKING -> drawSpeaking(canvas, cx, cy, core, ring)
            OrbState.MUTED -> drawMuted(canvas, cx, cy, core, ring)
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
        // Bright core + two offset ripples expanding past the ring.
        corePaint.color = listeningColor
        canvas.drawCircle(cx, cy, core, corePaint)
        ringPaint.color = withAlpha(listeningColor, 0.7f)
        canvas.drawCircle(cx, cy, ring, ringPaint)

        for (i in 0..1) {
            val phase = (ripplePhase + i * 0.5f) % 1f
            val rippleRadius = core + (ring - core) * phase + ring * 0.25f * phase
            val alpha = (1f - phase) * 0.35f
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
                cx - ring, cy - ring, cx + ring, cy + ring,
                start, 55f, false, ringPaint,
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

    private fun drawMuted(canvas: Canvas, cx: Float, cy: Float, core: Float, ring: Float) {
        corePaint.color = withAlpha(mutedColor, 0.4f)
        canvas.drawCircle(cx, cy, core, corePaint)
        ringPaint.color = withAlpha(mutedColor, 0.3f)
        canvas.drawCircle(cx, cy, ring, ringPaint)
    }

    private fun withAlpha(color: Int, alpha: Float): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)
}
