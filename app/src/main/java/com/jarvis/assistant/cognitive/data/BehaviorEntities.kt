package com.jarvis.assistant.cognitive.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * COGNITIVE_PLAN §5, migration v4→v5 (Phase 2 — temporal + behavioural).
 *
 * One executed tool call. Written by the CommandEventRecorder from the
 * ToolRegistry observer — every existing and future tool gets telemetry for
 * free, with NO utterance content: only the normalized slot payload
 * ([argsFingerprint]) plus mechanics (ok/latency). This is the raw material
 * for habit mining (§8.2) and the accept-reinforcement signal (§8.2: "a
 * matching user-executed command within 10 minutes of a suggestion").
 */
@Entity(
    tableName = "command_events",
    indices = [
        Index("at"),
        Index(value = ["tool", "at"]),
    ],
)
data class CommandEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Wall-clock execution time (ms). */
    val at: Long,
    /** Tool name as registered in the ToolRegistry ("playMusic", …). */
    val tool: String,
    /**
     * Normalized slot payload per [com.jarvis.assistant.cognitive.behavior.ArgFingerprints]
     * (e.g. `q:тарковский`) — clusters across phrasings; never raw free text.
     */
    val argsFingerprint: String,
    val ok: Boolean,
    val latencyMs: Long,
    /** VOICE | PROACTIVE | SCHEDULED (§8.1). Only VOICE feeds habit mining. */
    val origin: String,
) {
    companion object {
        const val ORIGIN_VOICE = "VOICE"
        const val ORIGIN_PROACTIVE = "PROACTIVE"
        const val ORIGIN_SCHEDULED = "SCHEDULED"
    }
}

/**
 * One mined habit candidate (§8.2). A rule says: "the user runs
 * [tool] with [argsFingerprint] around [hourBucket] (2-hour buckets)".
 *
 * Lifecycle: PROBATION (mined) → ACTIVE (first successful suggestion cycle:
 * an accept, or a fired suggestion that aged out without a rejection) →
 * MUTED (3 rejections, [mutedUntil] = +30 days) → back to ACTIVE, or RETIRED
 * (6 lifetime rejections). Recompute (HabitDetector) never resurrects a
 * MUTED/RETIRED rule and never touches its counters.
 *
 * Schema note: the plan lists no `mutedUntil` column; the 30-day MUTED
 * unmuting needs a timestamp, so this column is an additive, documented
 * deviation (nullable — NULL everywhere except MUTED rows).
 */
@Entity(
    tableName = "habit_rules",
    indices = [
        Index(value = ["tool", "argsFingerprint", "hourBucket", "kind"]),
        Index("state"),
    ],
)
data class HabitRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** TIME_WINDOW | DAY_SET (DAY_SET reserved — not mined until it earns it). */
    val kind: String,
    val tool: String,
    val argsFingerprint: String,
    /** 2-hour bucket 0..11 (bucket = hour / 2) for TIME_WINDOW rules. */
    val hourBucket: Int?,
    /** Comma-separated Calendar.DAY_OF_WEEK values for future DAY_SET rules. */
    val daySet: String?,
    /** Observed support when last recomputed (≥ the mining threshold). */
    val supportCount: Int,
    /** PROBATION | ACTIVE | MUTED | RETIRED */
    val state: String,
    val acceptCount: Int,
    val rejectCount: Int,
    val lastSuggestedAt: Long?,
    val lastFiredAt: Long?,
    val mutedUntil: Long?,
    val createdAt: Long,
) {
    companion object {
        const val KIND_TIME_WINDOW = "TIME_WINDOW"
        const val KIND_DAY_SET = "DAY_SET"
        const val STATE_PROBATION = "PROBATION"
        const val STATE_ACTIVE = "ACTIVE"
        const val STATE_MUTED = "MUTED"
        const val STATE_RETIRED = "RETIRED"
    }
}

/**
 * Every arbiter evaluation lands here — INCLUDING refusals (§8.3: "logs
 * every decision (including refusals to speak)"). 30-day retention.
 * DEFERRED rows are throttled (≤1 per rule per hour) so a wall-mounted
 * device idling in front of a TV cannot flood the table.
 */
@Entity(
    tableName = "behavior_log",
    indices = [Index("at"), Index("ruleId")],
)
data class BehaviorLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val ruleId: Long?,
    /** FIRED | BLOCKED | DEFERRED */
    val decision: String,
    /** Which gate fired (present, quota, cooldown, media, …) — audit value. */
    val reason: String,
    /** The suggestion text actually spoken — only for FIRED rows. */
    val utterance: String?,
) {
    companion object {
        const val DECISION_FIRED = "FIRED"
        const val DECISION_BLOCKED = "BLOCKED"
        const val DECISION_DEFERRED = "DEFERRED"
    }
}

/**
 * A summary of past conversation (§2.5/§7.1): SESSION = one summarize-
 * before-prune batch, DAILY = the nightly digest over the day's SESSION
 * rows. The SummarySection renders the latest DAILY plus the SESSION rows
 * that follow it, within a hard char budget.
 */
@Entity(
    tableName = "session_summaries",
    indices = [Index("kind"), Index("toAt")],
)
data class SessionSummaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** SESSION | DAILY */
    val kind: String,
    val fromMessageId: Long,
    val toMessageId: Long,
    val fromAt: Long,
    val toAt: Long,
    /** Russian summary text (the product language). */
    val text: String,
    /** Cloud model that produced it (stamped per plan §10.1 re-run rule). */
    val modelId: String,
    val tokensIn: Int,
    val tokensOut: Int,
    val createdAt: Long,
) {
    companion object {
        const val KIND_SESSION = "SESSION"
        const val KIND_DAILY = "DAILY"
    }
}
