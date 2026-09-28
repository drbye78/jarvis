package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the wake word that NEVER fired: sherpa-onnx's native
 * `KeywordSpotterResult.keyword` is DETOKENIZED (BPE `▁`→space, whitespace
 * stripped, uppercase), so the old `matchEntry` compared `▁JA R VI S` against
 * `"JARVIS"`, never matched, and the engine returned -1 in total silence.
 * The first four cases below fail under the pre-fix matcher.
 */
class SherpaKwsEngineMatchTest {

    private val entries = listOf(SherpaKeywords.wake(), SherpaKeywords.stop())

    private fun match(raw: String): SherpaKeywords.Entry? =
        SherpaKwsEngine.matchEntry(entries, raw)

    // ------------------------------------------------------------------
    // The required truth table
    // ------------------------------------------------------------------

    @Test
    fun `detokenized native phrase matches the wake entry`() {
        val m = match("JARVIS")
        assertEquals(SherpaKeywords.wake(), m)
        assertFalse("wake entry must not be a stop", m!!.isStop)
    }

    @Test
    fun `detokenized stop phrase matches the stop entry`() {
        val m = match("STOP")
        assertEquals(SherpaKeywords.stop(), m)
        assertTrue("stop entry must be a stop", m!!.isStop)
    }

    @Test
    fun `leading whitespace is tolerated`() {
        assertEquals(SherpaKeywords.wake(), match(" JA R VI S"))
    }

    @Test
    fun `trailing whitespace is tolerated`() {
        assertEquals(SherpaKeywords.wake(), match("JARVIS "))
    }

    @Test
    fun `case is tolerated`() {
        assertEquals(SherpaKeywords.wake(), match("jarvis"))
    }

    @Test
    fun `empty input matches nothing`() {
        assertNull(match(""))
    }

    @Test
    fun `an unrelated phrase matches nothing`() {
        assertNull(match("SOMETHING ELSE"))
    }

    // ------------------------------------------------------------------
    // A binding that still returns the raw token line keeps working.
    // ------------------------------------------------------------------

    @Test
    fun `raw underscore token line still matches after normalization`() {
        assertEquals(SherpaKeywords.wake(), match(SherpaKeywords.WAKE_TOKEN_LINE))
        assertEquals(SherpaKeywords.stop(), match(SherpaKeywords.STOP_TOKEN_LINE))
    }

    @Test
    fun `normalization strips the bpe marker and whitespace and uppercases`() {
        assertEquals(
            "JARVIS",
            SherpaKwsEngine.normalizeKeyword("\u2581JA R VI S"),
        )
        assertEquals("JARVIS", SherpaKwsEngine.normalizeKeyword(" jarvis "))
    }

    // ------------------------------------------------------------------
    // FIX 3: the sensitivity slider must actually govern the threshold,
    // anchored at the historical effective default (0.25) so removing the
    // per-line `#0.25` is not a recall regression.
    // ------------------------------------------------------------------

    @Test
    fun `default sensitivity reproduces the historical effective threshold`() {
        assertEquals(0.25f, SherpaKwsEngine.keywordsThresholdFor(0.6f), 1e-6f)
    }

    @Test
    fun `threshold mapping endpoints are pinned`() {
        assertEquals(0.05f, SherpaKwsEngine.keywordsThresholdFor(1.0f), 1e-6f)
        assertEquals(0.55f, SherpaKwsEngine.keywordsThresholdFor(0.0f), 1e-6f)
    }

    @Test
    fun `higher sensitivity means a lower (easier) threshold`() {
        val low = SherpaKwsEngine.keywordsThresholdFor(0.2f)
        val high = SherpaKwsEngine.keywordsThresholdFor(0.9f)
        assertTrue("expected $high < $low", high < low)
    }

    @Test
    fun `out-of-range sensitivity is clamped`() {
        assertEquals(SherpaKwsEngine.keywordsThresholdFor(1.0f), SherpaKwsEngine.keywordsThresholdFor(2.0f), 1e-6f)
        assertEquals(SherpaKwsEngine.keywordsThresholdFor(0.0f), SherpaKwsEngine.keywordsThresholdFor(-1.0f), 1e-6f)
    }
}
