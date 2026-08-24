package com.jarvis.assistant.util

/**
 * Reusable accumulating buffer for re-chunking audio frames from the mic's
 * native frame size (e.g. 320 samples) to a detector's native frame size
 * (e.g. 512 samples for Porcupine or Silero VAD), without per-frame
 * array allocations.
 */
class SampleAccumulator(private val chunkSize: Int) {
    private var buffer = ShortArray(chunkSize * 2)
    private var count = 0

    /**
     * Append a frame. Automatically grows the internal buffer if needed
     * (amortized — rare). Returns the current total count.
     */
    fun append(frame: ShortArray) {
        val needed = count + frame.size
        if (needed > buffer.size) {
            val newBuf = ShortArray(needed + chunkSize)
            System.arraycopy(buffer, 0, newBuf, 0, count)
            buffer = newBuf
        }
        System.arraycopy(frame, 0, buffer, count, frame.size)
        count += frame.size
    }

    /** Take the next [chunkSize] samples. Returns null if insufficient data. */
    fun take(): ShortArray? {
        if (count < chunkSize) return null
        val result = buffer.copyOfRange(0, chunkSize)
        val remaining = count - chunkSize
        System.arraycopy(buffer, chunkSize, buffer, 0, remaining)
        count = remaining
        return result
    }

    fun drainAll(): List<ShortArray> {
        val result = mutableListOf<ShortArray>()
        while (count >= chunkSize) {
            result.add(take()!!)
        }
        return result
    }

    fun reset() { count = 0 }
}