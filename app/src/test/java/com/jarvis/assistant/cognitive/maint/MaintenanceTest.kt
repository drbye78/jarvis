package com.jarvis.assistant.cognitive.maint

import com.jarvis.assistant.cognitive.model.FactCategory
import com.jarvis.assistant.cognitive.model.FactOrigin
import com.jarvis.assistant.cognitive.model.FactSnapshot
import com.jarvis.assistant.cognitive.model.FactStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §9.1: pure maintenance math (decay, cap, retention).
 */
class MaintenanceTest {

    private val now = 1_700_000_000_000L
    private val day = 86_400_000L

    private fun fact(
        factId: String,
        confidence: Float,
        ageDays: Long = 0,
        status: FactStatus = FactStatus.ACTIVE,
        lastRecalledAt: Long? = null,
    ) = FactSnapshot(
        factId = factId,
        category = FactCategory.OTHER,
        subject = "user",
        predicate = "p",
        value = factId,
        valueNormalized = factId,
        confidence = confidence,
        origin = FactOrigin.INFERRED,
        status = status,
        supersedesId = null,
        contested = false,
        sensitive = false,
        sourceMessageId = null,
        createdAt = now - (ageDays + 1) * day,
        updatedAt = now - ageDays * day,
        lastConfirmedAt = now - ageDays * day,
        lastRecalledAt = lastRecalledAt,
        recallCount = 0,
    )

    @Test
    fun `fresh facts do not decay`() {
        val f = fact("f1", 0.9f, ageDays = 0)
        assertEquals(0.9f, Maintenance.decayedConfidence(f, now), 1e-6f)
        val recent = fact("f2", 0.9f, ageDays = 29)
        assertEquals(0.9f, Maintenance.decayedConfidence(recent, now), 1e-6f)
    }

    @Test
    fun `decay applies after thirty idle days and floors at 0_2`() {
        // 60 idle days → 30 effective days of 0.99^30 ≈ 0.74.
        val f = fact("f", 0.9f, ageDays = 60)
        assertEquals(0.9f * Math.pow(0.99, 30.0), Maintenance.decayedConfidence(f, now).toDouble(), 0.01)
        // 10 years idle → floored.
        val ancient = fact("old", 0.9f, ageDays = 3650)
        assertEquals(Maintenance.CONFIDENCE_FLOOR, Maintenance.decayedConfidence(ancient, now), 1e-6f)
    }

    @Test
    fun `recall keeps a fact alive - lastRecalledAt counts as touching`() {
        // Updated 90 days ago but recalled yesterday → nearly no decay.
        val f = fact("used", 0.9f, ageDays = 90, lastRecalledAt = now - day)
        assertTrue(Maintenance.decayedConfidence(f, now) > 0.89f)
    }

    @Test
    fun `over cap archives the lowest scored surplus`() {
        val rows = (1..4).map { fact("weak$it", 0.3f) } // weakest first in DAO order
        val candidates = Maintenance.overCapArchiveCandidates(activeCount = 502, surplusRows = rows)
        assertEquals(2, candidates.size) // 502 − 500
        assertEquals("weak1", candidates[0])
    }

    @Test
    fun `below floor facts are archive candidates`() {
        val strong = fact("strong", 0.9f, ageDays = 31)
        val weak = fact("weak", 0.21f, ageDays = 40) // 0.21 × 0.99^10 ≈ 0.19 < floor
        val candidates = Maintenance.belowFloorArchiveCandidates(listOf(strong, weak), now)
        assertEquals(listOf("weak"), candidates)
    }

    @Test
    fun `expired superseded rows are deletable while fresh ones stay`() {
        val expired = fact("old-chain", 0.5f, ageDays = 120, status = FactStatus.SUPERSEDED)
        val fresh = fact("new-chain", 0.5f, ageDays = 5, status = FactStatus.SUPERSEDED)
        val active = fact("tip", 0.9f)
        val expiredIds = Maintenance.expiredSuperseded(listOf(expired, fresh, active), now)
        assertEquals(listOf("old-chain"), expiredIds)
    }
}
