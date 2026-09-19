package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.CommandEventDao
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleDao
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
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
    /**
     * P4.4 (REMEDIATION_PLAN): serialization for EVERY rule-row write of
     * this detector. The coordinator injects the SAME mutex its reject /
     * accept / fire paths use (P0.8 `ruleWriteMutex`), so the nightly /
     * ticker rule writes can no longer interleave a session-lane
     * read-modify-write on the same row (same lost-update class as the
     * reject race). Standalone constructions (tests) default to a private
     * mutex. kotlinx Mutex is NOT reentrant — never nest the three write
     * methods below into an outer `withLock` on the same instance.
     */
    private val ruleWriteMutex: Mutex = Mutex(),
) {

    /**
     * Recomputes rules from the trailing [lookbackDays] window.
     * @return number of rules created or updated (diagnostics only).
     */
    suspend fun recompute(lookbackDays: Int = LOOKBACK_DAYS): Int =
        ruleWriteMutex.withLock { recomputeLocked(lookbackDays) }

    /**
     * The whole nightly rule pass under ONE lock acquisition.
     *
     * P4.4 (REMEDIATION_PLAN F3): taking the mutex once makes recompute →
     * promote → unmute atomic with respect to the session lane's reject /
     * accept / fire paths. Calling the three public wrappers back-to-back
     * would be equally deadlock-free but would let a reject land BETWEEN the
     * passes, so promotion could read a pre-reject row and promote a rule the
     * user just pushed back on. kotlinx Mutex is NOT reentrant, so this calls
     * the `*Locked` bodies directly — never the wrappers.
     *
     * @return total rules touched across the three passes (diagnostics only).
     */
    suspend fun nightly(now: Long = nowMs()): Int = ruleWriteMutex.withLock {
        recomputeLocked(LOOKBACK_DAYS) + promoteProbationRulesLocked(now) + unmuteExpiredLocked(now)
    }

    private suspend fun recomputeLocked(lookbackDays: Int): Int {
        if (habitEligibleTools.isEmpty()) return 0
        val since = nowMs() - lookbackDays * DAY_MS
        val events = try {
            eventDao.voiceOkSince(since, habitEligibleTools.toList())
        } catch (e: CancellationException) {
            throw e // P1-C (A8): a cancelled recompute is not "telemetry unreadable"
        } catch (e: Exception) {
            // telemetry unreadable — habits simply wait for the next run
            // (content-free WARN: the DAO message can name tables, never facts)
            Timber.w(e, "Cognitive: habit telemetry read failed — recompute defers")
            return 0
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
    suspend fun promoteProbationRules(now: Long = nowMs()): Int =
        ruleWriteMutex.withLock { promoteProbationRulesLocked(now) }

    private suspend fun promoteProbationRulesLocked(now: Long): Int {
        var promoted = 0
        for (rule in ruleDao.candidateRules()) {
            // F2 (REMEDIATION_PLAN): a rule the user has ALREADY pushed back on
            // is never promoted by statistics. Aging out is only evidence of
            // "not annoying" when nobody ever said no — otherwise a rejected
            // suggestion gets promoted the first time the user ignores it, and
            // the mute/retire ladder restarts from a state we just blessed.
            val promotable = rule.state == HabitRuleEntity.STATE_PROBATION && rule.rejectCount == 0
            val firedAt = rule.lastFiredAt
            val agedOutClean = firedAt != null && firedAt < now - CYCLE_GRACE_MS
            if (promotable && (rule.acceptCount > 0 || agedOutClean)) {
                ruleDao.update(rule.copy(state = HabitRuleEntity.STATE_ACTIVE))
                promoted++
            }
        }
        return promoted
    }

    /** MUTED rules whose 30-day sentence elapsed return to ACTIVE (§8.2). */
    suspend fun unmuteExpired(now: Long = nowMs()): Int =
        ruleWriteMutex.withLock { unmuteExpiredLocked(now) }

    private suspend fun unmuteExpiredLocked(now: Long): Int {
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
