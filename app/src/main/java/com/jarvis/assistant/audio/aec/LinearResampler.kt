package com.jarvis.assistant.audio.aec

/**
 * Stateful streaming linear resampler (mono, int16).
 *
 * Converts between arbitrary rates (built for 24 kHz TTS → 16 kHz mic-rate)
 * with linear interpolation and EXACT rational pacing: the k-th output
 * sample interpolates the input at position k·(inRate/outRate) — integer
 * bookkeeping, so long streams never drift and chunking boundaries behave
 * identically to one big block.
 *
 * Pure Kotlin, JVM-testable; no anti-alias windowing (speech-band content:
 * the AEC's delay search and adaptive filter tolerate the interpolation
 * artifacts, documented in RUNBOOK).
 */
class LinearResampler(
    val inRate: Int,
    val outRate: Int,
) {
    init {
        require(inRate > 0 && outRate > 0) { "rates must be positive: $inRate -> $outRate" }
    }

    /** The last input sample of the previous block (interpolation memory). */
    private var last: Float = 0f
    private var primed = false

    /** Total input samples consumed (absolute index space = [0, N)). */
    private var n = 0L

    /** Rational numerator of the NEXT output's input position: pos = num / outRate. */
    private var num = 0L

    /** Total output samples produced. */
    private var produced = 0L

    val outProduced: Long get() = produced

    /**
     * Resample one block. Emits every output sample whose interpolation
     * window (floor(p), floor(p)+1) is fully available; the final partial
     * window waits for the next block (that is what makes chunking exact).
     */
    fun process(block: ShortArray): ShortArray {
        if (block.isEmpty()) return ShortArray(0)
        val bn = block.size
        val out = ArrayList<Int>(bn) // int16 values; length known only after
        // Available absolute samples after this block: [0, n+bn) — with only
        // x(n-1) ("last") and the block itself (x(n..n+bn-1)) in memory.
        while (true) {
            val i0 = (num / outRate).toInt()          // floor(pos)
            val need = i0 + 1                          // lookahead sample index
            if (need > n + bn - 1) break               // wait for more input
            val frac = ((num % outRate).toDouble() / outRate).toFloat()
            val s0 = readSample(i0, n, bn, block)
            val s1 = readSample(need, n, bn, block)
            val v = s0 + (s1 - s0) * frac
            out.add(clamp16(v).toInt())
            produced++
            num += inRate
        }
        // If the lookahead spilled INTO this block's future we still advanced
        // n correctly below; partial windows carry over via num.
        last = block[bn - 1].toFloat()
        primed = true
        n += bn
        val arr = ShortArray(out.size)
        for (i in out.indices) arr[i] = out[i].toShort()
        return arr
    }

    /** Absolute sample i: the previous block's tail or this block. */
    private fun readSample(i: Int, blockStart: Long, blockLen: Int, block: ShortArray): Float =
        if (i < blockStart.toInt()) last // only i == blockStart-1 is reachable
        else block[i - blockStart.toInt()].toFloat()

    private fun clamp16(v: Float): Short =
        v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    fun reset() {
        last = 0f
        primed = false
        n = 0L
        num = 0L
        produced = 0L
    }
}
