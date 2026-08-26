package com.jarvis.assistant.audio

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Small bounded buffer of recent frames used for pre-subscription recovery:
 * when a listening session opens, frames captured between the wake word and
 * stream start are drained so the first word is not clipped.
 *
 * Frames stored here must be private snapshots (see [AudioPipeline]).
 *
 * Capacity derives from [com.jarvis.assistant.config.JarvisConfig.preRollMs]
 * (M8) — see [AudioPipeline.ringCapacity]. Every frame dropped because the
 * buffer overflowed increments [evictionCount] so clipped-audio incidents are
 * diagnosable after the fact.
 */
class AudioRingBuffer(val capacity: Int) {
    private val deque = ConcurrentLinkedDeque<ShortArray>()
    private val evicted = AtomicLong()

    /** Total unread pre-roll frames dropped because the buffer overflowed. */
    val evictionCount: Long get() = evicted.get()

    fun add(frame: ShortArray) {
        deque.addLast(frame)
        while (deque.size > capacity) {
            deque.pollFirst() ?: break
            evicted.incrementAndGet()
        }
    }

    fun drain(): List<ShortArray> {
        val result = mutableListOf<ShortArray>()
        while (true) {
            val item = deque.pollFirst() ?: break
            result.add(item)
        }
        return result
    }
}
