package com.jarvis.assistant

import com.jarvis.assistant.di.AppGraph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS far-end tap burst-truncation regression (audit fix): the tap used to
 * push an ENTIRE resampled chunk in one
 * [com.jarvis.assistant.audio.aec.FarEndMixer.onFrame]; the mixer's per-lane
 * queue cap is 12 slots × 320 samples ≈ 240 ms, and a single frame larger
 * than the whole cap was dropped ENTIRELY by the overflow drop — every chunk
 * longer than 240 ms self-truncated (far-end reference gap visible as
 * `droppedFarEndFrames`). The tap now splits into ≤ 320-sample frames via
 * [AppGraph.chunkIntoSlotFrames]; these tests pin the splitter.
 */
class AppGraphFarEndTapTest {

    @Test
    fun `exact multiple of the slot size splits into full frames`() {
        val samples = ShortArray(3 * AppGraph.FAR_END_SLOT_SAMPLES) { (it % 32767).toShort() }
        val frames = AppGraph.chunkIntoSlotFrames(samples)

        assertEquals(3, frames.size)
        assertTrue(frames.all { it.size == AppGraph.FAR_END_SLOT_SAMPLES })
        assertEquals(samples.toList(), frames.flatMap { it.toList() })
    }

    @Test
    fun `remainder lands in a short tail frame`() {
        val samples = ShortArray(2 * AppGraph.FAR_END_SLOT_SAMPLES + 97) { (it / 2).toShort() }
        val frames = AppGraph.chunkIntoSlotFrames(samples)

        assertEquals(3, frames.size)
        assertEquals(AppGraph.FAR_END_SLOT_SAMPLES, frames[0].size)
        assertEquals(AppGraph.FAR_END_SLOT_SAMPLES, frames[1].size)
        assertEquals(97, frames[2].size)
        assertEquals(samples.toList(), frames.flatMap { it.toList() })
    }

    @Test
    fun `shorter than one slot is passed through as a single frame`() {
        val samples = ShortArray(123) { it.toShort() }
        val frames = AppGraph.chunkIntoSlotFrames(samples)

        assertEquals(1, frames.size)
        assertEquals(samples.toList(), frames.single().toList())
    }

    @Test
    fun `empty input yields no frames`() {
        assertTrue(AppGraph.chunkIntoSlotFrames(ShortArray(0)).isEmpty())
    }

    @Test
    fun `no produced frame exceeds the mixer slot size`() {
        // A >240 ms TTS chunk (the self-truncation case): 700 ms @ 16 kHz.
        val samples = ShortArray(11_200)
        for (frame in AppGraph.chunkIntoSlotFrames(samples)) {
            assertTrue(
                "frame size ${frame.size} exceeds the mixer slot",
                frame.size <= AppGraph.FAR_END_SLOT_SAMPLES,
            )
        }
    }
}
