package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.cognitive.extract.FakeCommandEventDao
import com.jarvis.assistant.cognitive.extract.FakeHabitRuleDao
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §8.2: mining, reinforcement states and the plan's seeded
 * acceptance shape — 6 days of the same command at the same hour must
 * produce a PROBATION rule that fires at that hour on day 7.
 */
class HabitDetectorTest {

    private val eligible = setOf("playMusic", "getWeather", "getNowPlaying")

    private fun event(tool: String, fingerprint: String, at: Long, ok: Boolean = true) =
        CommandEventEntity(at = at, tool = tool, argsFingerprint = fingerprint, ok = ok, latencyMs = 100, origin = "VOICE")

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
