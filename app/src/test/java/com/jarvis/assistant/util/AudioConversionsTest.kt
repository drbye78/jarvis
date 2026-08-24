package com.jarvis.assistant.util

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioConversionsTest {

    @Test fun `roundtrip ShortArray to ByteArray and back`() {
        val original = shortArrayOf(0, 100, -100, 32767, -32768)
        val bytes = original.toByteArray()
        assertEquals(original.size * 2, bytes.size)
        val recovered = bytes.toShortArray()
        assertArrayEquals(original, recovered)
    }

    @Test fun `empty array`() {
        val original = shortArrayOf()
        assertEquals(0, original.toByteArray().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `odd byte array length throws`() {
        byteArrayOf(0).toShortArray()
    }

    @Test fun `zeroes roundtrip`() {
        val original = ShortArray(100)
        assertArrayEquals(original, original.toByteArray().toShortArray())
    }
}
