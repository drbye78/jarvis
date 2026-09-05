package com.jarvis.assistant.cognitive.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §10.1: the extraction quality gate. Runs the starter
 * fixture set through the REAL validator + normalizer.
 *
 * Gate decision (recorded in RUNBOOK): `memory.autoExtract` stays DEFAULT
 * OFF until the full 40-fixture set (per Appendix C) passes precision ≥ 0.85
 * / recall ≥ 0.7 with zero hallucinations. The starter set below already
 * enforces the gate so a validator regression fails CI.
 */
class ExtractionEvalTest {

    private val harness = ExtractionEvalHarness()

    @Test
    fun `starter fixture set passes the extraction gate`() {
        val fixtures = EvalFixtures.load()
        assertTrue("fixtures must be present on the test classpath", fixtures.isNotEmpty())
        assertEquals(14, fixtures.size)

        val metrics = harness.evaluate(fixtures)
        assertTrue(
            "gate failed: $metrics — see COGNITIVE_PLAN §10.1",
            metrics.precision >= 0.85,
        )
        assertTrue("gate failed: $metrics", metrics.recall >= 0.7)
        assertEquals(
            "zero hallucinations on the anti-hallucination probe set (plan §10.1)",
            0,
            metrics.hallucinations,
        )
    }

    @Test
    fun `noise fixtures extract nothing at all`() {
        val fixtures = EvalFixtures.load().filter { it.kind == "noise" }
        val metrics = harness.evaluate(fixtures)
        assertEquals(0, metrics.truePositives + metrics.falsePositives)
    }
}
