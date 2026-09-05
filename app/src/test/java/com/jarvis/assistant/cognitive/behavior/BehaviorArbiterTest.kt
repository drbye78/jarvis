package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.BehaviorLogEntity
import com.jarvis.assistant.cognitive.data.CommandEventEntity
import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §8.3: every gate, one test. FIRED only when ALL seven pass;
 * gates 3/4 defer; everything else blocks with its name in the reason.
 */
class BehaviorArbiterTest {

    private val rule = HabitRuleEntity(
        id = 7,
        kind = HabitRuleEntity.KIND_TIME_WINDOW,
        tool = "playMusic",
        argsFingerprint = "q:джаз",
        hourBucket = 10,
        daySet = null,
        supportCount = 9,
        state = HabitRuleEntity.STATE_ACTIVE,
        acceptCount = 0,
        rejectCount = 0,
        lastSuggestedAt = null,
        lastFiredAt = null,
        mutedUntil = null,
        createdAt = 0,
    )

    private val green = BehaviorArbiter.ArbiterContext(
        behaviorEnabled = true,
        quietHoursActive = false,
        dndActive = false,
        batteryOk = true,
        sessionIdle = true,
        mediaActive = false,
        recentInteraction = true,
        quotaLeft = true,
        cooldownOk = true,
        notRecentlyDelivered = true,
    )

    private fun ctx(mutate: (BehaviorArbiter.ArbiterContext) -> BehaviorArbiter.ArbiterContext) =
        mutate(green)

    @Test
    fun `all gates green fires`() {
        assertTrue(BehaviorArbiter.evaluate(rule, green) is BehaviorArbiter.Decision.Fired)
    }

    @Test
    fun `gate 1 master switch off blocks`() {
        val d = BehaviorArbiter.evaluate(rule, ctx { it.copy(behaviorEnabled = false) })
        assertEquals("disabled", (d as BehaviorArbiter.Decision.Blocked).reason)
    }

    @Test
    fun `gate 1 quiet hours block`() {
        val d = BehaviorArbiter.evaluate(rule, ctx { it.copy(quietHoursActive = true) })
        assertEquals("quiet_hours", (d as BehaviorArbiter.Decision.Blocked).reason)
    }

    @Test
    fun `gate 2 dnd and battery block`() {
        val dnd = BehaviorArbiter.evaluate(rule, ctx { it.copy(dndActive = true) })
        val battery = BehaviorArbiter.evaluate(rule, ctx { it.copy(batteryOk = false) })
        assertEquals("dnd", (dnd as BehaviorArbiter.Decision.Blocked).reason)
        assertEquals("battery", (battery as BehaviorArbiter.Decision.Blocked).reason)
    }

    @Test
    fun `gate 3 busy session defers`() {
        val d = BehaviorArbiter.evaluate(rule, ctx { it.copy(sessionIdle = false) })
        assertEquals("session_busy", (d as BehaviorArbiter.Decision.Deferred).reason)
    }

    @Test
    fun `gate 4 media defers`() {
        val d = BehaviorArbiter.evaluate(rule, ctx { it.copy(mediaActive = true) })
        assertEquals("media", (d as BehaviorArbiter.Decision.Deferred).reason)
    }

    @Test
    fun `gate 5 no recent presence blocks`() {
        val d = BehaviorArbiter.evaluate(rule, ctx { it.copy(recentInteraction = false) })
        assertEquals("no_recent_presence", (d as BehaviorArbiter.Decision.Blocked).reason)
    }

    @Test
    fun `gate 6 cooldown and quota block`() {
        assertEquals(
            "cooldown",
            (BehaviorArbiter.evaluate(rule, ctx { it.copy(cooldownOk = false) }) as BehaviorArbiter.Decision.Blocked).reason,
        )
        assertEquals(
            "daily_quota",
            (BehaviorArbiter.evaluate(rule, ctx { it.copy(quotaLeft = false) }) as BehaviorArbiter.Decision.Blocked).reason,
        )
    }

    @Test
    fun `gate 7 muted rule blocks even if a candidate slips through`() {
        val d = BehaviorArbiter.evaluate(
            rule.copy(state = HabitRuleEntity.STATE_MUTED, mutedUntil = Long.MAX_VALUE),
            green,
        )
        assertEquals("rule_muted", (d as BehaviorArbiter.Decision.Blocked).reason)
    }

    @Test
    fun `gate 7 recent delivery blocks`() {
        val d = BehaviorArbiter.evaluate(rule, ctx { it.copy(notRecentlyDelivered = false) })
        assertEquals("delivered_recently", (d as BehaviorArbiter.Decision.Blocked).reason)
    }

    @Test
    fun `quiet hour window semantics`() {
        // Default 23→8 wraps midnight.
        assertTrue(BehaviorArbiter.isQuietHour(23, 23, 8))
        assertTrue(BehaviorArbiter.isQuietHour(3, 23, 8))
        assertTrue(BehaviorArbiter.isQuietHour(7, 23, 8))
        assertTrue(!BehaviorArbiter.isQuietHour(8, 23, 8))
        assertTrue(!BehaviorArbiter.isQuietHour(12, 23, 8))
        // Plain daytime window.
        assertTrue(BehaviorArbiter.isQuietHour(13, 13, 15))
        assertTrue(!BehaviorArbiter.isQuietHour(15, 13, 15))
        // Equal start/end disables quiet hours.
        assertTrue(!BehaviorArbiter.isQuietHour(5, 8, 8))
    }

    @Test
    fun `log rows carry the utterance only when fired`() {
        val fired = BehaviorArbiter.toLogRow(BehaviorArbiter.Decision.Fired, 7, now = 1, utterance = "Ты обычно…")
        assertEquals(BehaviorLogEntity.DECISION_FIRED, fired.decision)
        assertEquals("Ты обычно…", fired.utterance)

        val blocked = BehaviorArbiter.toLogRow(
            BehaviorArbiter.Decision.Blocked("quota"),
            7,
            now = 1,
            utterance = "should be dropped",
        )
        assertEquals(BehaviorLogEntity.DECISION_BLOCKED, blocked.decision)
        assertEquals("quota", blocked.reason)
        assertNull(blocked.utterance)

        val deferred = BehaviorArbiter.toLogRow(BehaviorArbiter.Decision.Deferred("media"), 7, now = 1)
        assertEquals(BehaviorLogEntity.DECISION_DEFERRED, deferred.decision)
    }

    @Test
    fun `start of day is local midnight`() {
        val cal = java.util.Calendar.getInstance().apply {
            set(2026, java.util.Calendar.SEPTEMBER, 5, 14, 30, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        val midnight = BehaviorArbiter.startOfDayMs(cal.timeInMillis)
        val check = java.util.Calendar.getInstance().apply { timeInMillis = midnight }
        assertEquals(0, check.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(0, check.get(java.util.Calendar.MINUTE))
    }
}

/** Telemetry rows keep the mechanics only (§8.1 shape sanity). */
class CommandEventShapeTest {
    @Test
    fun `origin constants are stable storage layout`() {
        assertEquals("VOICE", CommandEventEntity.ORIGIN_VOICE)
        assertEquals("PROACTIVE", CommandEventEntity.ORIGIN_PROACTIVE)
        assertEquals("SCHEDULED", CommandEventEntity.ORIGIN_SCHEDULED)
    }
}
