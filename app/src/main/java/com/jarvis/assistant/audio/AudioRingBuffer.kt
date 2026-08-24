package com.jarvis.assistant.audio

import java.util.concurrent.ConcurrentLinkedDeque

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
