package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.CommandEventDao
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleDao
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import java.util.Calendar
import java.util.TimeZone

/**
 * COGNITIVE_PLAN §8.2: mines [HabitRuleEntity] rows out of `command_events`.
 *
 * Runs in nightly maintenance and after every 10th recorded event (the
 * recorder drives both). Clustering is PURE KOTLIN over the raw VOICE/ok
 * events: the plan's SQL groups by `strftime('%H', …, 'localtime')`, but
 * SQLite's localtime is a device-dependent gamble — the same 2-hour bucket
 * math here is deterministic, injectable-clock testable (§4 "pure Kotlin,
 * injected Clock, fully fixture-testable").
 *
 * State discipline (§8.2):
 * - a NEW cluster enters as PROBATION;
 * - an existing PROBATION/ACTIVE rule just gets its support count refreshed;
 * - MUTED/RETIRED rules are NEVER touched by recompute — a user's "stop
 *   suggesting this" must not be undone by statistics.
 */
class HabitDetector(
    private val eventDao: CommandEventDao,
    private val ruleDao: HabitRuleDao,
    private val habitEligibleTools: Set<String>,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Recomputes rules from the trailing [lookbackDays] window.
     * @return number of rules created or updated (diagnostics only).
     */
    suspend fun recompute(lookbackDays: Int = LOOKBACK_DAYS): Int {
        if (habitEligibleTools.isEmpty()) return 0
        val since = nowMs() - lookbackDays * DAY_MS
        val events = try {
            eventDao.voiceOkSince(since, habitEligibleTools.toList())
        } catch (_: Exception) {
            return 0 // telemetry unreadable — habits simply wait for the next run
        }
        if (events.isEmpty()) return 0

        val clusters = cluster(events)
        var touched = 0
        for ((key, support) in clusters) {
            touched += upsertCluster(key, support)
        }
        return touched
    }

    /** Returns 1 when the cluster created or updated a rule, 0 otherwise. */
    private suspend fun upsertCluster(key: ClusterKey, support: Int): Int {
        val existing = ruleDao.byKey(key.tool, key.fingerprint, key.hourBucket, HabitRuleEntity.KIND_TIME_WINDOW)
            ?: return insertNewRule(key, support)
        // User feedback (MUTED/RETIRED) outranks statistics — never touched.
        if (existing.state == HabitRuleEntity.STATE_MUTED || existing.state == HabitRuleEntity.STATE_RETIRED) {
            return 0
        }
        if (existing.supportCount != support) {
            ruleDao.update(existing.copy(supportCount = support))
            return 1
        }
        return 0
    }

    private suspend fun insertNewRule(key: ClusterKey, support: Int): Int {
        if (support < MIN_SUPPORT) return 0
        ruleDao.insert(
            HabitRuleEntity(
                kind = HabitRuleEntity.KIND_TIME_WINDOW,
                tool = key.tool,
                argsFingerprint = key.fingerprint,
                hourBucket = key.hourBucket,
                daySet = null,
                supportCount = support,
                state = HabitRuleEntity.STATE_PROBATION,
                acceptCount = 0,
                rejectCount = 0,
                lastSuggestedAt = null,
                lastFiredAt = null,
                mutedUntil = null,
                createdAt = nowMs(),
            ),
        )
        return 1
    }

    /**
     * §8.2 promotion: a PROBATION rule becomes ACTIVE after its first
     * successful suggestion cycle — an explicit accept, or a fired
     * suggestion that aged out (30 min) without a rejection.
     */
    suspend fun promoteProbationRules(now: Long = nowMs()): Int {
        var promoted = 0
        for (rule in ruleDao.candidateRules()) {
            if (rule.state != HabitRuleEntity.STATE_PROBATION) continue
            val fired = rule.lastFiredAt != null
            val accepted = rule.acceptCount > 0
            val agedOutClean = fired && rule.lastFiredAt!! < now - CYCLE_GRACE_MS
            if (accepted || agedOutClean) {
                ruleDao.update(rule.copy(state = HabitRuleEntity.STATE_ACTIVE))
                promoted++
            }
        }
        return promoted
    }

    /** MUTED rules whose 30-day sentence elapsed return to ACTIVE (§8.2). */
    suspend fun unmuteExpired(now: Long = nowMs()): Int {
        var unmuted = 0
        for (rule in ruleDao.all()) {
            val until = rule.mutedUntil
            if (rule.state == HabitRuleEntity.STATE_MUTED && until != null && until <= now) {
                ruleDao.update(rule.copy(state = HabitRuleEntity.STATE_ACTIVE, mutedUntil = null))
                unmuted++
            }
        }
        return unmuted
    }

    /**
     * Groups events by (tool, fingerprint, hourBucket) using the DEVICE
     * timezone (the same clock the arbiter will evaluate against), keeping
     * clusters that reach [MIN_SUPPORT].
     */
    internal fun cluster(events: List<CommandEventEntity>): Map<ClusterKey, Int> {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        val counts = HashMap<ClusterKey, Int>()
        for (event in events) {
            calendar.timeInMillis = event.at
            val key = ClusterKey(
                tool = event.tool,
                fingerprint = event.argsFingerprint,
                hourBucket = ArgFingerprints.hourBucket(calendar.get(Calendar.HOUR_OF_DAY)),
            )
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.filterValues { it >= MIN_SUPPORT }
    }

    /** Clustering identity — hourBucket participates, kind is fixed here. */
    data class ClusterKey(val tool: String, val fingerprint: String, val hourBucket: Int)

    companion object {
        /** §8.2: HAVING c >= 5. */
        const val MIN_SUPPORT = 5

        /** Mining window: two weeks of telemetry is plenty for a wall device. */
        const val LOOKBACK_DAYS = 14

        /** "First successful suggestion cycle" aging grace (see KDoc). */
        const val CYCLE_GRACE_MS = 30 * 60_000L

        private const val DAY_MS = 24 * 60 * 60_000L
    }
}
