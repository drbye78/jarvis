package com.jarvis.assistant.contracts

/**
 * Fixed-capacity buffer of the most recent frames. Used by AudioPipeline so a
 * late-subscribing VAD collector can recover frames emitted just before it attached.
 */
class RingBuffer<T>(private val capacity: Int) {
    private val items = ArrayDeque<T>()

    fun add(item: T) {
        items.addLast(item)
        while (items.size > capacity) items.removeFirst()
    }

    fun drain(): List<T> = items.toList().also { items.clear() }
    fun snapshot(): List<T> = items.toList()
}
