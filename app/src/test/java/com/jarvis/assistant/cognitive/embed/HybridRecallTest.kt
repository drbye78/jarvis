package com.jarvis.assistant.cognitive.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HybridRecallTest {

    @Test
    fun `empty primary falls back to secondary and vice versa`() {
        assertEquals(listOf("x", "y"), HybridRecall.rrfFuse(emptyList(), listOf("x", "y")))
        assertEquals(listOf("x", "y"), HybridRecall.rrfFuse(listOf("x", "y"), emptyList()))
    }

    @Test
    fun `fact in both channels outranks either alone`() {
        // primary: a > b > c ; secondary: z > a
        val fused = HybridRecall.rrfFuse(
            listOf("a", "b", "c"),
            listOf("z", "a"),
        )
        assertEquals("a", fused.first())
        assertTrue(fused.containsAll(listOf("b", "c", "z")))
    }

    @Test
    fun `single-channel items keep their channel order`() {
        val fused = HybridRecall.rrfFuse(listOf("a", "b", "c"), listOf("d"))
        // a and b and c keep relative order; d appended by score.
        assertEquals("a", fused[0])
        assertTrue(fused.indexOf("b") < fused.indexOf("c"))
        assertTrue(fused.contains("d"))
    }

    @Test
    fun `deterministic for identical inputs`() {
        val a = HybridRecall.rrfFuse(listOf("p", "q", "r"), listOf("q", "s", "p"))
        val b = HybridRecall.rrfFuse(listOf("p", "q", "r"), listOf("q", "s", "p"))
        assertEquals(a, b)
    }

    @Test
    fun `rank weighting favors top ranks`() {
        // The secondary top-1 must beat the primary rank-9 (1/61 < 1/69…):
        // secondary rank0 = 1/(60+1)=0.0164 > primary rank9 = 1/(60+10)=0.0143.
        val primary = (0 until 10).map { "p$it" }
        val secondary = listOf("s0")
        val fused = HybridRecall.rrfFuse(primary, secondary)
        assertTrue("s0 should beat p9", fused.indexOf("s0") < fused.indexOf("p9"))
    }

    @Test
    fun `custom k shifts the decay`() {
        val fused = HybridRecall.rrfFuse(listOf("a", "b"), listOf("b", "a"), k = 1)
        assertEquals(listOf("a", "b"), fused) // tie → primary order
    }
}
