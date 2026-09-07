package com.jarvis.assistant.cognitive.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * COGNITIVE_PLAN §10.1: the extraction quality gate. Runs the FULL
 * 40-fixture set (Appendix C) through the REAL validator + normalizer.
 *
 * Gate decision (recorded in RUNBOOK): `memory.autoExtract` stays DEFAULT
 * OFF until the full 40-fixture set passes precision ≥ 0.85 / recall ≥ 0.7
 * with zero hallucinations. The suite below enforces the gate so a
 * validator regression fails CI; fixtures are expanded from real device
 * transcripts over time (honest scope note in [ExtractionEvalHarness]).
 */
class ExtractionEvalTest {

    private val harness = ExtractionEvalHarness()

    @Test
    fun `full fixture set passes the extraction gate`() {
        val fixtures = EvalFixtures.load()
        assertTrue("fixtures must be present on the test classpath", fixtures.isNotEmpty())
        assertEquals(40, fixtures.size)

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
