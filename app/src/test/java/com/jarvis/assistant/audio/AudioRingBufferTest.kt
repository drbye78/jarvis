package com.jarvis.assistant.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AudioRingBufferTest {

    @Test fun `capacity enforcement`() {
        val buffer = AudioRingBuffer(3)
        buffer.add(ShortArray(1) { 1 })
        buffer.add(ShortArray(1) { 2 })
        buffer.add(ShortArray(1) { 3 })
        buffer.add(ShortArray(1) { 4 })
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(2.toShort(), drained[0][0])
        assertEquals(3.toShort(), drained[1][0])
        assertEquals(4.toShort(), drained[2][0])
    }

    @Test fun `drain returns all and clears`() {
        val buffer = AudioRingBuffer(5)
        buffer.add(ShortArray(1) { 10 })
        buffer.add(ShortArray(1) { 20 })
        assertEquals(2, buffer.drain().size)
        assertTrue(buffer.drain().isEmpty())
    }

    @Test fun `concurrent add and drain`() = runBlocking {
        val buffer = AudioRingBuffer(100)
        val producer = async {
            for (i in 1..1000) buffer.add(ShortArray(1) { i.toShort() })
        }
        val drainedCounts = async {
            var total = 0
            repeat(10) {
                total += buffer.drain().size
                kotlinx.coroutines.delay(1)
            }
            total
        }
        producer.await()
        // Final drain
        val remaining = buffer.drain().size
        val drainedFromAsync = drainedCounts.await()
        assertEquals(100, remaining + drainedFromAsync)
    }
}
