package com.jarvis.assistant.cognitive.behavior

/**
 * The home-notice gate matrix (R4/H4).
 *
 * It reuses the habit lane's [BehaviorArbiter.Decision] vocabulary, its
 * [BehaviorArbiter.ArbiterContext] carrier and its numeric helpers/constants,
 * but has its OWN master switch (`awarenessEnabled`) and its OWN daily cap. It
 * deliberately does NOT reuse [BehaviorArbiter.evaluate]: that function is
 * rule-bound (72 h/24 h habit cooldowns, rule state) and pinned by its own
 * tests; forcing a synthetic rule through it would corrupt `behavior_log` with
 * `FIRED` rows that consume the habit quota and mask real suggestions in the
 * reject path.
 *
 * PURE — every input is a boolean, so the matrix is exhaustive and unit-testable.
 *
 * Two deviations from the habit matrix, both intentional:
 *  - `awarenessEnabled` replaces `ctx.behaviorEnabled` (the two Settings toggles
 *    are labelled independently; the habit switch must not secretly gate the
 *    home lane);
 *  - `session_busy`/`media` BLOCK rather than DEFER — a home event is
 *    instantaneous and stale 15 minutes later, so there is no same-day recheck.
 */
object HomeNoticeGate {

    // The gate matrix reads best as flat ordered early-returns — one gate, one
    // line, one reason. Restructuring to satisfy a count limit would bury the
    // semantics (same precedent as BehaviorArbiter.evaluate).
    @Suppress("ReturnCount")
    fun evaluate(
        ctx: BehaviorArbiter.ArbiterContext,
        awarenessEnabled: Boolean,
        quotaLeft: Boolean,
        cooldownOk: Boolean,
    ): BehaviorArbiter.Decision {
        if (!awarenessEnabled) return BehaviorArbiter.Decision.Blocked("disabled")
        if (ctx.quietHoursActive) return BehaviorArbiter.Decision.Blocked("quiet_hours")
        if (ctx.dndActive) return BehaviorArbiter.Decision.Blocked("dnd")
        if (!ctx.batteryOk) return BehaviorArbiter.Decision.Blocked("battery")
        if (!ctx.sessionIdle) return BehaviorArbiter.Decision.Blocked("session_busy")
        if (ctx.mediaActive) return BehaviorArbiter.Decision.Blocked("media")
        if (!ctx.recentInteraction) return BehaviorArbiter.Decision.Blocked("no_recent_presence")
        if (!quotaLeft) return BehaviorArbiter.Decision.Blocked("daily_quota")
        if (!cooldownOk) return BehaviorArbiter.Decision.Blocked("cooldown")
        return BehaviorArbiter.Decision.Fired
    }
}
