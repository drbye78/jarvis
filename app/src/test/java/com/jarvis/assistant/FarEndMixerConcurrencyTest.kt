package com.jarvis.assistant

import com.jarvis.assistant.audio.aec.FarEndMixer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * P1-S #5(a) (audit 2026-09-16): FarEndMixer used to guard its lane map with
 * TWO monitors — `@Synchronized` on `this` for lane()/reset() versus the
 * internal `lock` for onFrame()/drainSlot() — so a reset() iterating
 * [lanes] raced a getOrPut insertion (ConcurrentModificationException on the
 * producer) and lost read-modify-write updates of activeSlots. With ONE
 * monitor for all map state, hammering every method concurrently must be
 * exception-free and leave deterministic, sane state.
 */
class FarEndMixerConcurrencyTest {

    @Test
    fun `concurrent onFrame, drainSlot, lane and reset never corrupt lane state`() {
        val mixer = FarEndMixer()
        mixer.lane("tts")
        val stop = AtomicBoolean(false)
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val frame = ShortArray(320) { 100 }
        val started = CountDownLatch(5)

        // Three feeders push under ROTATING lane ids — each new id grows the
        // LinkedHashMap, the exact mutation reset() used to iterate unsafely.
        val feeders = (1..3).map { n ->
            Thread {
                started.countDown()
                try {
                    var i = 0
                    while (!stop.get()) {
                        mixer.onFrame("lane-$n-${i % 4}", frame)
                        i++
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                }
            }
        }
        val drainer = Thread {
            started.countDown()
            try {
                while (!stop.get()) {
                    val slot = mixer.drainSlot()
                    if (slot.size != 320) errors.add(AssertionError("slot of ${slot.size} samples"))
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        val resetter = Thread {
            started.countDown()
            try {
                while (!stop.get()) {
                    mixer.reset()
                    mixer.lane("tts")
                }
            } catch (t: Throwable) {
                errors.add(t)
            }
        }
        (feeders + drainer + resetter).forEach { it.start() }
        assertTrue("threads failed to start", started.await(5, TimeUnit.SECONDS))
        Thread.sleep(1_500)
        stop.set(true)
        (feeders + drainer + resetter).forEach { it.join(10_000) }

        assertEquals("concurrent mixer errors: $errors", 0, errors.size)

        // Stable state after quiescing: reset clears, one active frame counts.
        mixer.reset()
        assertEquals(0L, mixer.activeSlots)
        mixer.onFrame("x", frame)
        val slot = mixer.drainSlot()
        assertEquals(320, slot.size)
        assertEquals("fed lane must fill the slot", 100.toShort(), slot[0])
        assertEquals("one active slot after reset", 1L, mixer.activeSlots)
    }

    @Test
    fun `reset between frame and drain drops the queued reference`() {
        // The lane-state half of the protocol (the drop counter is a lifetime
        // diagnostic and deliberately SURVIVES — pinned in NlmsEchoCancellerTest).
        val mixer = FarEndMixer()
        mixer.onFrame("tts", ShortArray(320) { 100 })
        mixer.reset()
        assertEquals(0, mixer.drainSlot().sum().toInt())
    }
}
