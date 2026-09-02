package com.jarvis.assistant.audio.aec

/**
 * Time-slotted far-end reference mixer.
 *
 * Multiple far-end lanes (own-TTS tap, playback capture) produce audio
 * aperiodically and at their own pace. The mic pipeline consumes exactly one
 * 20 ms slot per [drainSlot] call at the real-time capture cadence (50 Hz),
 * so the mixer paces EVERY lane to the mic's time grid:
 *
 * - Each lane owns a pending-frame queue; [onFrame] appends (bounded —
 *   overflow drops oldest, a stalled consumer must not grow without bound).
 * - [drainSlot] consumes exactly [slotSamples] from every lane per call —
 *   starving lanes contribute zeros (their speaker was silent), burst-fed
 *   lanes are consumed at real-time rate (no time compression).
 * - The slot output is the SUM of all lanes (int16 wrap-clamped), because
 *   simultaneous lanes are mixed acoustically at the speaker too.
 *
 * The residual bulk delay between this electrical grid and what the mic
 * actually hears is estimated afterwards by [DelayAligner] inside the
 * canceller — the mixer only guarantees a common, non-drifting time base.
 *
 * Thread-safety: [onFrame] may be called from any lane's thread (TTS actor,
 * capture pump); [drainSlot] is producer-only.
 */
class FarEndMixer(
    private val slotSamples: Int = 320,
    private val maxQueuedSlotsPerLane: Int = 12, // 240 ms jitter tolerance
) {
    private class Lane {
        val pending = ArrayDeque<ShortArray>()
        var pendingSamples = 0
        /** Sub-slot remainder of the frame being consumed (float to sum exactly). */
        var carry: FloatArray? = null
        var carryOffset = 0
        var carryLen = 0
    }

    private val lanes = LinkedHashMap<String, Lane>()
    private val lock = Any()

    /** Number of slots where at least one lane delivered energy. */
    var activeSlots: Long = 0L
        private set

    /** Last slot's total far-end energy (sum of squares, int16 scale). */
    @Volatile var lastSlotEnergy: Double = 0.0
        private set

    /** Ensure a far-end lane exists. Idempotent; not required before [onFrame]. */
    @Synchronized
    fun lane(id: String) {
        lanes.getOrPut(id) { Lane() }
    }

    /**
     * Push one far-end frame from [id] (any length; 16 kHz mono PCM).
     * Frame contents are copied — caller owns its buffer.
     */
    fun onFrame(id: String, frame: ShortArray) {
        if (frame.isEmpty()) return
        synchronized(lock) {
            val lane = lanes.getOrPut(id) { Lane() }
            lane.pending.addLast(frame.copyOf())
            lane.pendingSamples += frame.size
            while (lane.pendingSamples > maxQueuedSlotsPerLane * slotSamples) {
                val dropped = lane.pending.removeFirstOrNull() ?: break
                lane.pendingSamples -= dropped.size
            }
        }
    }

    /**
     * Consume one 20 ms slot: exactly [slotSamples] mixed samples.
     * Producer-thread only (called from [EchoCanceller.process]).
     */
    fun drainSlot(): ShortArray {
        val out = ShortArray(slotSamples)
        var energy = 0.0
        synchronized(lock) {
            for (lane in lanes.values) {
                var written = 0
                while (written < slotSamples) {
                    if (lane.carry == null || lane.carryLen <= 0) {
                        val next = lane.pending.removeFirstOrNull() ?: break
                        lane.pendingSamples -= next.size
                        val f = FloatArray(next.size)
                        for (i in next.indices) f[i] = next[i].toFloat()
                        lane.carry = f
                        lane.carryOffset = 0
                        lane.carryLen = next.size
                    }
                    val c = lane.carry!!
                    val take = minOf(slotSamples - written, lane.carryLen)
                    for (i in 0 until take) {
                        val v = c[lane.carryOffset + i]
                        val mixed = out[written + i] + v
                        out[written + i] = mixed.toInt().coerceIn(
                            Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt(),
                        ).toShort()
                    }
                    lane.carryOffset += take
                    lane.carryLen -= take
                    written += take
                    if (lane.carryLen <= 0) {
                        lane.carry = null
                        lane.carryOffset = 0
                    }
                }
            }
        }
        for (s in out) energy += (s * s).toDouble()
        lastSlotEnergy = energy
        if (energy > ACTIVE_ENERGY_FLOOR) activeSlots++
        return out
    }

    /** Drop all lane state (mode switch / reset). */
    @Synchronized
    fun reset() {
        for (lane in lanes.values) {
            lane.pending.clear()
            lane.pendingSamples = 0
            lane.carry = null
            lane.carryOffset = 0
            lane.carryLen = 0
        }
        activeSlots = 0
        lastSlotEnergy = 0.0
    }

    private companion object {
        /** Below this a slot counts as silent (int16² scale, ~-60 dBFS). */
        const val ACTIVE_ENERGY_FLOOR = 320.0 * 4.0
    }
}
