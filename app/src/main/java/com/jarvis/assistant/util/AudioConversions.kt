package com.jarvis.assistant.util

/**
 * Little-endian 16-bit PCM conversions between [ShortArray] (samples) and
 * [ByteArray] (raw bytes), used by the VAD collector and TTS player.
 */

/** Encodes this array of 16-bit signed samples as little-endian bytes. */
fun ShortArray.toByteArray(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFFFF
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = (v shr 8 and 0xFF).toByte()
    }
    return out
}

/** Decodes little-endian 16-bit PCM bytes into signed samples. */
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
