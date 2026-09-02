package com.jarvis.assistant.audio.aec

/**
 * Block-normalized cross-correlation delay estimation between the mic signal
 * and the far-end reference history.
 *
 * Bulk acoustic delay (speaker→mic path + playout buffer) can be tens of ms;
 * without alignment the adaptive filter wastes tap coverage on it. This
 * estimator searches the far-end ring buffer over [searchWindowMs] and
 * returns the best-correlation lag in samples.
 *
 * Pure math, JVM-testable: [estimate] takes de-meaned frames and a far-end
 * history lookup.
 */
object DelayAligner {

    /**
     * Find the lag (in mic samples) that maximizes normalized
     * cross-correlation between the mic frame and the far-end signal,
     * with the far-end DELAYED by that lag matching the mic.
     *
     * @param mic de-meaned recent mic samples (one or a few concatenated
     *        20 ms frames — longer windows give stabler estimates).
     * @param farEndHistory far-end samples covering at least
     *        [searchLagSamples] + mic.size samples BEFORE the mic frame's
     *        capture time, ordered oldest→newest, ending exactly at the
     *        mic frame's capture point.
     * @param searchLagSamples max lag to consider (positive = far-end leads
     *        mic, i.e. audio played in the past is heard now).
     * @return best positive lag in samples, or null when no far-end energy /
     *        correlation is too weak to trust.
     */
    fun estimate(
        mic: FloatArray,
        farEndHistory: FloatArray,
        searchLagSamples: Int,
        minCorrelation: Float = 0.35f,
    ): Int? {
        require(searchLagSamples in 0..(farEndHistory.size - mic.size)) {
            "search window $searchLagSamples exceeds far-end history ${farEndHistory.size} vs mic ${mic.size}"
        }
        val micNorm = sqrtSumSq(mic)
        if (micNorm < 1e-6f) return null

        var bestLag: Int? = null
        var bestScore = 0f
        // Far-end candidate window = last (lag + mic.size) samples, sliding.
        // mic[i] should equal farEnd[len - lag - mic.size + i].
        val len = farEndHistory.size
        var lag = searchLagSamples
        while (lag >= 0) {
            val start = len - lag - mic.size
            if (start < 0) break
            var dot = 0f
            var fe = 0f
            var i = 0
            var j = start
            while (i < mic.size) {
                val f = farEndHistory[j]
                dot += mic[i] * f
                fe += f * f
                i++
                j++
            }
            val feNorm = sqrtf(fe)
            if (feNorm > 1e-6f) {
                val score = dot / (micNorm * feNorm)
                if (score > bestScore) {
                    bestScore = score
                    bestLag = lag
                }
            }
            lag -= LAG_STRIDE
        }
        return if (bestScore >= minCorrelation) bestLag else null
    }

    /**
     * De-mean + convert a PCM frame to floats ([-1,1]-ish scale kept in
     * short units to avoid precision loss — correlation is scale-invariant).
     */
    fun toDemeanedFloats(frame: ShortArray): FloatArray {
        if (frame.isEmpty()) return FloatArray(0)
        var mean = 0.0
        for (s in frame) mean += s
        mean /= frame.size
        val out = FloatArray(frame.size)
        for (i in frame.indices) out[i] = (frame[i] - mean).toFloat()
        return out
    }

    private fun sqrtSumSq(x: FloatArray): Float {
        var acc = 0f
        for (v in x) acc += v * v
        return sqrtf(acc)
    }

    private fun sqrtf(v: Float): Float = Math.sqrt(v.toDouble()).toFloat()

    /** Lag search stride (samples) — coarse-to-fine is unnecessary at 16 kHz. */
    const val LAG_STRIDE = 8

    /** Default search window: ±250 ms of plausible bulk delay. */
    const val DEFAULT_SEARCH_MS = 250L
}
