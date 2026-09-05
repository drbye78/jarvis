package com.jarvis.assistant.cognitive.embed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {

    @Test
    fun `float codec round-trips`() {
        val v = floatArrayOf(0f, 1f, -0.5f, 3.14159f, -123.75f, 1e-8f, Float.MIN_VALUE)
        val decoded = VectorMath.bytesToFloats(VectorMath.floatsToBytes(v))
        assertTrue(v.contentEquals(decoded))
    }

    @Test
    fun `bytesToFloats rejects non-multiple-of-4`() {
        try {
            VectorMath.bytesToFloats(ByteArray(5))
            throw AssertionError("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `normalize produces unit length and keeps direction`() {
        val v = floatArrayOf(3f, 4f)
        val n = VectorMath.l2Normalize(v)
        assertEquals(1f, VectorMath.dot(n, n), 1e-6f)
        assertEquals(0.6f, n[0], 1e-6f)
        assertEquals(0.8f, n[1], 1e-6f)
    }

    @Test
    fun `normalize of zero vector stays zero`() {
        val n = VectorMath.l2Normalize(floatArrayOf(0f, 0f))
        assertEquals(0f, n[0], 0f)
        assertEquals(0f, n[1], 0f)
    }

    @Test
    fun `topK orders by similarity desc and breaks ties by id`() {
        val query = floatArrayOf(1f, 0f)
        val candidates = listOf(
            "b" to floatArrayOf(0.9f, 0.1f),
            "a" to floatArrayOf(0.9f, 0.1f),
            "c" to floatArrayOf(0.1f, 0.9f),
            "d" to floatArrayOf(1f, 0f),
        )
        val top = VectorMath.topK(query, candidates, k = 3)
        assertEquals(listOf("d", "a", "b"), top)
    }

    @Test
    fun `topK with zero k or no candidates is empty`() {
        assertTrue(VectorMath.topK(floatArrayOf(1f), emptyList(), 3).isEmpty())
        assertTrue(
            VectorMath.topK(floatArrayOf(1f), listOf("a" to floatArrayOf(1f)), 0).isEmpty(),
        )
    }

    @Test
    fun `dot handles short vectors safely`() {
        assertEquals(3f, VectorMath.dot(floatArrayOf(1f, 2f, 3f), floatArrayOf(1f, 1f)), 1e-6f)
    }
}
