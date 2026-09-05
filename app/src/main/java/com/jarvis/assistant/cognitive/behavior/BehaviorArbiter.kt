package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.BehaviorLogEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity

/**
 * COGNITIVE_PLAN §8.3: the ordered gate matrix that decides whether a habit
 * rule may speak. PURE — every gate consumes one boolean from
 * [ArbiterContext] (assembled by the coordinator from live signals), so each
 * gate is independently unit-testable and the matrix is exhaustive:
 *
 * FIRED | DEFERRED (gates 3/4 — busy/media — re-checked the same day)
 * | BLOCKED (everything else, with the gate name in the reason).
 *
 * Every evaluation — including refusals — is logged to `behavior_log` (§8.3
 * "logs every decision (including refusals to speak)"; DEFERRED rows are
 * throttled by the caller so an idle device cannot flood the table).
 */
object BehaviorArbiter {

    /** §8.3 gate 1..7 verdicts. */
    sealed interface Decision {
        data object Fired : Decision
        data class Deferred(val reason: String) : Decision
        data class Blocked(val reason: String) : Decision
    }

    /**
     * One evaluation's snapshot. The coordinator builds this from the live
     * StateFlows / device signals right before calling [evaluate]; tests
     * build it by hand.
     */
    data class ArbiterContext(
        /** Gate 1: master switch (default OFF per §12.4-1). */
        val behaviorEnabled: Boolean,
        /** Gate 1: inside the user's quiet hours (default 23:00–08:00). */
        val quietHoursActive: Boolean,
        /** Gate 2. */
        val dndActive: Boolean,
        /** Gate 2: battery > 15% OR charging (wall device: usually satisfied). */
        val batteryOk: Boolean,
        /** Gate 3: session state machine is IDLE. */
        val sessionIdle: Boolean,
        /** Gate 4: no external media playing. */
        val mediaActive: Boolean,
        /** Gate 5: a session interaction happened within the last 4 h. */
        val recentInteraction: Boolean,
        /** Gate 6: global FIRED count today is below the daily quota. */
        val quotaLeft: Boolean,
        /** Gate 6: rule cooldown (72 h) elapsed. */
        val cooldownOk: Boolean,
        /** Gate 7: this exact suggestion was not delivered in the last 24 h. */
        val notRecentlyDelivered: Boolean,
    )

    // The gate matrix reads best as flat ordered early-returns — one gate,
    // one line, one reason. Restructuring to satisfy a count limit would
    // bury the semantics the plan specifies.
    @Suppress("ReturnCount")
    fun evaluate(rule: HabitRuleEntity, ctx: ArbiterContext): Decision {
        // Gate 1 — master switch, quiet hours.
        if (!ctx.behaviorEnabled) return Decision.Blocked("disabled")
        if (ctx.quietHoursActive) return Decision.Blocked("quiet_hours")

        // Gate 2 — DND / battery.
        if (ctx.dndActive) return Decision.Blocked("dnd")
        if (!ctx.batteryOk) return Decision.Blocked("battery")

        // Gates 3/4 — never collide with a live session or media playback.
        // These two DEFER (re-checked the same day) instead of blocking outright.
        if (!ctx.sessionIdle) return Decision.Deferred("session_busy")
        if (ctx.mediaActive) return Decision.Deferred("media")

        // Gate 5 — do not talk to an empty room.
        if (!ctx.recentInteraction) return Decision.Blocked("no_recent_presence")

        // Gate 6 — cooldown + global daily quota.
        if (!ctx.cooldownOk) return Decision.Blocked("cooldown")
        if (!ctx.quotaLeft) return Decision.Blocked("daily_quota")

        // Gate 7 — rule state (defense in depth: the candidate query already
        // filters MUTED/RETIRED) + 24 h per-suggestion freshness.
        if (rule.state == HabitRuleEntity.STATE_MUTED || rule.state == HabitRuleEntity.STATE_RETIRED) {
            return Decision.Blocked("rule_${rule.state.lowercase()}")
        }
        if (!ctx.notRecentlyDelivered) return Decision.Blocked("delivered_recently")

        return Decision.Fired
    }

    /** Convenience: decision → behavior_log row (utterance only when FIRED). */
    fun toLogRow(
        decision: Decision,
        ruleId: Long?,
        now: Long,
        utterance: String? = null,
    ): BehaviorLogEntity = BehaviorLogEntity(
        at = now,
        ruleId = ruleId,
        decision = when (decision) {
            Decision.Fired -> BehaviorLogEntity.DECISION_FIRED
            is Decision.Deferred -> BehaviorLogEntity.DECISION_DEFERRED
            is Decision.Blocked -> BehaviorLogEntity.DECISION_BLOCKED
        },
        reason = when (decision) {
            Decision.Fired -> "all_gates_passed"
            is Decision.Deferred -> decision.reason
            is Decision.Blocked -> decision.reason
        },
        utterance = utterance?.takeIf { decision == Decision.Fired },
    )

    // §8.3 numeric gates — constants shared by the coordinator and tests.
    const val COOLDOWN_MS = 72 * 60 * 60_000L
    const val DELIVERY_FRESHNESS_MS = 24 * 60 * 60_000L
    const val PRESENCE_WINDOW_MS = 4 * 60 * 60_000L
    const val DEFAULT_DAILY_QUOTA = 2

    /**
     * Gate 1 helper: is [hour] inside the user's quiet hours?
     * [quietStart] inclusive, [quietEnd] exclusive; a window that wraps
     * midnight (23→8) is the default; equal start/end disables quiet hours.
     */
    fun isQuietHour(hour: Int, quietStart: Int, quietEnd: Int): Boolean = when {
        quietStart == quietEnd -> false
        quietStart < quietEnd -> hour in quietStart until quietEnd
        else -> hour >= quietStart || hour < quietEnd
    }

    /** Local-midnight start of the day containing [at] (gate 6 quota window). */
    fun startOfDayMs(at: Long): Long = java.util.Calendar.getInstance().apply {
        timeInMillis = at
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
}
