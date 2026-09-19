package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.CommandEventDao
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.extract.FakeCommandEventDao
import com.jarvis.assistant.cognitive.extract.FakeHabitRuleDao
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * COGNITIVE_PLAN §8.2: mining, reinforcement states and the plan's seeded
 * acceptance shape — 6 days of the same command at the same hour must
 * produce a PROBATION rule that fires at that hour on day 7.
 */
class HabitDetectorTest {

    private val eligible = setOf("playMusic", "getWeather", "getNowPlaying")

    private fun event(tool: String, fingerprint: String, at: Long, ok: Boolean = true) =
        CommandEventEntity(
            at = at,
            tool = tool,
            argsFingerprint = fingerprint,
            ok = ok,
            latencyMs = 100,
            origin = "VOICE"
        )

    private fun day7At20() = 6L * 24 * 60 * 60_000L + 20L * 60 * 60_000L

    private fun seedSixDays(events: FakeCommandEventDao, now: Long) {
        // 6 days × 6 events at ~20:00 (hour bucket 10), same fingerprint.
        for (day in 0 until 6) {
            for (i in 0 until 6) {
                events.rows += event(
                    "playMusic",
                    "q:джаз",
                    now - (6 - day) * 24 * 60 * 60_000L + i * 60_000L,
                )
            }
        }
    }

    @Test
    fun `six days of the same hourly command mine one probation rule`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)

        val detector = HabitDetector(events, rules, eligible, nowMs = { now })
        val touched = detector.recompute()

        assertEquals(1, touched)
        val rule = rules.rows.values.single()
        assertEquals(HabitRuleEntity.STATE_PROBATION, rule.state)
        assertEquals("playMusic", rule.tool)
        assertEquals("q:джаз", rule.argsFingerprint)
        assertEquals(36, rule.supportCount)
        assertTrue(rule.hourBucket != null)
    }

    @Test
    fun `below the support threshold nothing is mined`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        for (i in 0 until 4) events.rows += event("playMusic", "q:джаз", now - i * 60_000L)

        HabitDetector(events, rules, eligible, nowMs = { now }).recompute()
        assertTrue(rules.rows.isEmpty())
    }

    @Test
    fun `failed or non-voice events never mine`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        for (i in 0 until 10) events.rows += event("playMusic", "q:джаз", now - i * 60_000L, ok = false)
        events.rows += event("playMusic", "q:джаз", now, ok = true).copy(origin = "PROACTIVE")

        HabitDetector(events, rules, eligible, nowMs = { now }).recompute()
        assertTrue(rules.rows.isEmpty())
    }

    @Test
    fun `ineligible tools are never mined`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        for (i in 0 until 10) events.rows += event("setVolume", "level:25", now - i * 60_000L)

        HabitDetector(events, rules, eligible, nowMs = { now }).recompute()
        assertTrue(rules.rows.isEmpty())
    }

    @Test
    fun `recompute refreshes support but never resurrects muted rules`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)
        val detector = HabitDetector(events, rules, eligible, nowMs = { now })
        detector.recompute()
        val original = rules.rows.values.single()

        // The user muted the rule; statistics must not undo that.
        rules.rows[original.id] = original.copy(state = HabitRuleEntity.STATE_MUTED, mutedUntil = Long.MAX_VALUE)
        detector.recompute()
        assertEquals(HabitRuleEntity.STATE_MUTED, rules.rows.values.single().state)
    }

    @Test
    fun `an accept graduates probation to active`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)
        val detector = HabitDetector(events, rules, eligible, nowMs = { now })
        detector.recompute()
        val rule = rules.rows.values.single()

        rules.rows[rule.id] = rule.copy(acceptCount = 1)
        detector.promoteProbationRules(now)
        assertEquals(HabitRuleEntity.STATE_ACTIVE, rules.rows.values.single().state)
    }

    @Test
    fun `a fired suggestion that ages out clean graduates too`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)
        val detector = HabitDetector(events, rules, eligible, nowMs = { now })
        detector.recompute()
        val rule = rules.rows.values.single()

        rules.rows[rule.id] = rule.copy(lastFiredAt = now - HabitDetector.CYCLE_GRACE_MS - 1)
        detector.promoteProbationRules(now)
        assertEquals(HabitRuleEntity.STATE_ACTIVE, rules.rows.values.single().state)
    }

    // ---- F2 (REMEDIATION_PLAN): a rejected rule is never blessed ----------

    @Test
    fun `a rejected probation rule never promotes even after a clean fire`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)
        val detector = HabitDetector(events, rules, eligible, nowMs = { now })
        detector.recompute()
        val rule = rules.rows.values.single()

        // The user pushed back once, then ignored a later suggestion that aged
        // out: pre-fix, aging out promoted it and the mute ladder restarted.
        rules.rows[rule.id] = rule.copy(
            rejectCount = 1,
            lastFiredAt = now - HabitDetector.CYCLE_GRACE_MS - 1,
        )
        detector.promoteProbationRules(now)
        assertEquals(HabitRuleEntity.STATE_PROBATION, rules.rows.values.single().state)

        // And the reject counter still wins over an explicit accept-shaped
        // signal: only rejectCount == 0 may promote.
        rules.rows[rule.id] = rules.rows.getValue(rule.id).copy(acceptCount = 1)
        detector.promoteProbationRules(now)
        assertEquals(HabitRuleEntity.STATE_PROBATION, rules.rows.values.single().state)
    }

    // ---- F3 (REMEDIATION_PLAN): one lock acquisition for the nightly pass --

    @Test
    fun `nightly runs all three rule passes and returns their total`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)
        val detector = HabitDetector(events, rules, eligible, nowMs = { now })

        // recompute mines 1 rule; the same pass then unmutes an unrelated
        // expired rule, proving nightly chains the passes rather than only
        // running the first one.
        val expired = HabitRuleEntity(
            id = 999,
            kind = HabitRuleEntity.KIND_TIME_WINDOW,
            tool = "getWeather",
            argsFingerprint = "city:москва",
            hourBucket = 10,
            daySet = null,
            supportCount = 9,
            state = HabitRuleEntity.STATE_MUTED,
            acceptCount = 2,
            rejectCount = 3,
            lastSuggestedAt = null,
            lastFiredAt = null,
            mutedUntil = now - 1,
            createdAt = 0,
        )
        rules.rows[expired.id] = expired

        val touched = detector.nightly(now)

        assertEquals("recompute(1) + unmute(1)", 2, touched)
        assertEquals(HabitRuleEntity.STATE_ACTIVE, rules.rows.getValue(999L).state)
        assertEquals(null, rules.rows.getValue(999L).mutedUntil)
        assertEquals("one mined rule + the pre-existing one", 2, rules.rows.size)
    }

    @Test
    fun `muted rules return after their 30-day sentence`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        seedSixDays(events, now)
        HabitDetector(events, rules, eligible, nowMs = { now }).recompute()
        val rule = rules.rows.values.single()

        val mutedUntil = now + 29 * 24 * 60 * 60_000L
        rules.rows[rule.id] = rule.copy(state = HabitRuleEntity.STATE_MUTED, mutedUntil = mutedUntil)
        HabitDetector(events, rules, eligible, nowMs = { now }).unmuteExpired(now)
        assertEquals(HabitRuleEntity.STATE_MUTED, rules.rows.values.single().state)

        HabitDetector(events, rules, eligible, nowMs = { mutedUntil + 1 }).unmuteExpired(mutedUntil + 1)
        val after = rules.rows.values.single()
        assertEquals(HabitRuleEntity.STATE_ACTIVE, after.state)
        assertEquals(null, after.mutedUntil)
    }

    @Test
    fun `different fingerprints stay separate rules`() = runBlocking {
        val events = FakeCommandEventDao()
        val rules = FakeHabitRuleDao()
        val now = day7At20()
        for (i in 0 until 6) {
            events.rows += event("playMusic", "q:джаз", now - i * 60_000L)
            events.rows += event("getWeather", "city:москва", now - i * 60_000L)
        }
        HabitDetector(events, rules, eligible, nowMs = { now }).recompute()
        assertEquals(2, rules.rows.size)
    }

    // ---- P1-C (audit HIGH, A8): cancellation contract -------------------

    /** WARN+-counting tree (numeric priors — no android.util.Log on JVM). */
    private class CapturingTree : Timber.Tree() {
        val lines = mutableListOf<Pair<Int, String>>()
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            synchronized(lines) { if (priority >= 5) lines.add(priority to message) }
        }
    }

    @Test
    fun `recompute propagates cancellation instead of deferring silently`() {
        val tree = CapturingTree()
        Timber.uprootAll()
        Timber.plant(tree)
        try {
            val events = object : CommandEventDao by FakeCommandEventDao() {
                override suspend fun voiceOkSince(since: Long, tools: List<String>):
                    List<CommandEventEntity> = throw CancellationException("shutdown")
            }
            val rules = FakeHabitRuleDao()
            val error = runCatching {
                runBlocking {
                    HabitDetector(events, rules, eligible, nowMs = { day7At20() }).recompute()
                }
            }.exceptionOrNull()
            // Old code: the CE was caught as "Exception" → WARN + return 0,
            // masking shutdown as "telemetry unreadable".
            assertTrue(
                "cancellation must escape recompute (old code swallowed it)",
                error is CancellationException,
            )
            assertEquals("a cancel is not a failure — no WARN/ERROR may be logged", 0, tree.lines.size)
        } finally {
            Timber.uprootAll()
        }
    }

    @Test
    fun `a genuine telemetry failure defers with a content-free WARN`() {
        val tree = CapturingTree()
        Timber.uprootAll()
        Timber.plant(tree)
        try {
            val events = object : CommandEventDao by FakeCommandEventDao() {
                override suspend fun voiceOkSince(
                    since: Long,
                    tools: List<String>,
                ): List<CommandEventEntity> = error("db down")
            }
            val rules = FakeHabitRuleDao()
            val touched = runBlocking {
                HabitDetector(events, rules, eligible, nowMs = { day7At20() }).recompute()
            }
            assertEquals("genuine failure defers the pass", 0, touched)
            assertTrue(rules.rows.isEmpty())
            assertEquals(1, tree.lines.size)
            val (priority, message) = tree.lines.single()
            assertEquals("Timber.w maps to WARN (5)", 5, priority)
            assertTrue(
                "the WARN must carry no event/tool content",
                message.contains("habit telemetry read failed") &&
                    !message.contains("playMusic") && !message.contains("q:"),
            )
        } finally {
            Timber.uprootAll()
        }
    }
}

/** §8.4: deterministic templates — a proposal, never an action. */
class ProactivePresenterTest {

    private val strings = ToolStrings.Default

    private fun rule(tool: String, fingerprint: String) = HabitRuleEntity(
        id = 1,
        kind = HabitRuleEntity.KIND_TIME_WINDOW,
        tool = tool,
        argsFingerprint = fingerprint,
        hourBucket = 10,
        daySet = null,
        supportCount = 9,
        state = HabitRuleEntity.STATE_PROBATION,
        acceptCount = 0,
        rejectCount = 0,
        lastSuggestedAt = null,
        lastFiredAt = null,
        mutedUntil = null,
        createdAt = 0,
    )

    @Test
    fun `music suggestion names the query and asks`() = runBlocking {
        val text = ProactivePresenter.render(rule("playMusic", "q:джаз"), strings)
        assertEquals("Ты обычно слушаешь «джаз» в это время. Включить?", text)
        assertTrue(text.endsWith("?"))
    }

    @Test
    fun `weather suggestion names the city`() = runBlocking {
        val text = ProactivePresenter.render(rule("getWeather", "city:москва"), strings)
        assertEquals("Ты обычно смотришь погоду в «москва» в это время. Показать?", text)
    }

    @Test
    fun `generic suggestion uses the tool label`() = runBlocking {
        val text = ProactivePresenter.render(rule("listPlaylists", "all"), strings)
        assertEquals("Ты часто просишь список плейлистов в это время. Повторить?", text)
    }
}
