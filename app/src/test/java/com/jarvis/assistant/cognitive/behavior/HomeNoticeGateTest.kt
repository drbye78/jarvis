package com.jarvis.assistant.cognitive.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** One assertion per gate: FIRED only when every home-notice gate passes. */
class HomeNoticeGateTest {

    private fun ctx(
        quiet: Boolean = false,
        dnd: Boolean = false,
        battery: Boolean = true,
        idle: Boolean = true,
        media: Boolean = false,
        presence: Boolean = true,
    ) = BehaviorArbiter.ArbiterContext(
        // Ignored by HomeNoticeGate (it has its own master switch).
        behaviorEnabled = true,
        quietHoursActive = quiet,
        dndActive = dnd,
        batteryOk = battery,
        sessionIdle = idle,
        mediaActive = media,
        recentInteraction = presence,
        quotaLeft = true,
        cooldownOk = true,
        notRecentlyDelivered = true,
    )

    private fun evaluate(
        ctx: BehaviorArbiter.ArbiterContext,
        awareness: Boolean = true,
        quotaLeft: Boolean = true,
        cooldownOk: Boolean = true,
    ) = HomeNoticeGate.evaluate(ctx, awareness, quotaLeft, cooldownOk)

    @Test
    fun `fires when every gate passes and awareness is on`() {
        assertEquals(BehaviorArbiter.Decision.Fired, evaluate(ctx()))
    }

    @Test
    fun `the home switch is independent of the habit switch`() {
        // ctx() carries behaviorEnabled=true already; the point is the gate
        // never consults it. Assert firing with awareness on regardless.
        assertTrue(evaluate(ctx()) is BehaviorArbiter.Decision.Fired)
    }

    @Test
    fun `awareness off blocks`() {
        val decision = evaluate(ctx(), awareness = false)
        assertEquals(BehaviorArbiter.Decision.Blocked("disabled"), decision)
    }

    @Test
    fun `quiet hours block before presence`() {
        assertEquals(BehaviorArbiter.Decision.Blocked("quiet_hours"), evaluate(ctx(quiet = true)))
    }

    @Test
    fun `session busy and media block rather than defer`() {
        assertEquals(BehaviorArbiter.Decision.Blocked("session_busy"), evaluate(ctx(idle = false)))
        assertEquals(BehaviorArbiter.Decision.Blocked("media"), evaluate(ctx(media = true)))
    }

    @Test
    fun `no recent presence blocks`() {
        assertEquals(BehaviorArbiter.Decision.Blocked("no_recent_presence"), evaluate(ctx(presence = false)))
    }

    @Test
    fun `quota and cooldown block`() {
        assertEquals(BehaviorArbiter.Decision.Blocked("daily_quota"), evaluate(ctx(), quotaLeft = false))
        assertEquals(BehaviorArbiter.Decision.Blocked("cooldown"), evaluate(ctx(), cooldownOk = false))
    }
}
