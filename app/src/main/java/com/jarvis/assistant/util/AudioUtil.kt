package com.jarvis.assistant.util

/**
 * Reusable accumulating buffer for re-chunking audio frames from the mic's
 * 320-sample frame to a detector's native frame size (512 for Porcupine /
 * Silero VAD), without per-frame array allocations.
 */
class SampleAccumulator(private val chunkSize: Int) {
    private var buffer = ShortArray(chunkSize * 2)
    private var count = 0

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

    fun take(): ShortArray? {
        if (count < chunkSize) return null
        val result = buffer.copyOfRange(0, chunkSize)
        val remaining = count - chunkSize
        System.arraycopy(buffer, chunkSize, buffer, 0, remaining)
        count = remaining
        return result
    }

    fun reset() {
        count = 0
    }
}

/** Little-endian 16-bit PCM conversions. */

fun ShortArray.toByteArray(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFFFF
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
    }
    return out
}

fun ByteArray.toShortArray(): ShortArray {
    require(size % 2 == 0) { "ByteArray length must be even for 16-bit PCM" }
    val out = ShortArray(size / 2)
    for (i in out.indices) {
        val lo = this[i * 2].toInt() and 0xFF
        val hi = this[i * 2 + 1].toInt() and 0xFF
        out[i] = (lo or (hi shl 8)).toShort()
    }
    return out
}
