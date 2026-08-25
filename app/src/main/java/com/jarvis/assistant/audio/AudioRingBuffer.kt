package com.jarvis.assistant.audio

import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Small bounded buffer of recent frames used for pre-subscription recovery:
 * when a listening session opens, frames captured between the wake word and
 * stream start are drained so the first word is not clipped.
 *
 * Frames stored here must be private snapshots (see [AudioPipeline]).
 */
class AudioRingBuffer(private val capacity: Int) {
    private val deque = ConcurrentLinkedDeque<ShortArray>()

    fun add(frame: ShortArray) {
        deque.addLast(frame)
        while (deque.size > capacity) deque.pollFirst()
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
