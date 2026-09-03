package com.jarvis.assistant

import com.jarvis.assistant.audio.AssistantAudioFocus
import com.jarvis.assistant.audio.AssistantFocusState
import com.jarvis.assistant.audio.AudioFocusAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 (M6): the duck-focus state machine. The contract under test:
 * focus is requested at the FIRST sentence of a TTS generation, abandoned
 * at the LAST drained sentence, force-abandoned on barge-in flush, and
 * re-requested by the next generation. Denial and count races degrade
 * safely — the machine can never wedge in DUCKING or abandon twice.
 */
class AssistantAudioFocusTest {

    private class RecordingAdapter(var granted: Boolean = true) : AudioFocusAdapter {
        var requests = 0
        var abandons = 0

        override fun requestDuckFocus(): Boolean {
            requests++
            return granted
        }

        override fun abandonFocus() { abandons++ }
    }

    @Test
    fun `first sentence requests, last sentence abandons`() {
        val adapter = RecordingAdapter()
        val focus = AssistantAudioFocus(adapter)

        focus.onTtsSentenceStarted()
        assertEquals(AssistantFocusState.DUCKING, focus.state)
        focus.onTtsSentenceStarted() // concurrent prefetch sentence
        assertEquals(1, adapter.requests) // requested exactly once

        focus.onTtsSentenceFinished()
        assertEquals(AssistantFocusState.DUCKING, focus.state) // still one active
        focus.onTtsSentenceFinished()
        assertEquals(AssistantFocusState.IDLE, focus.state)
        assertEquals(1, adapter.requests)
        assertEquals(1, adapter.abandons)
    }

    @Test
    fun `flush abandons immediately and zeroes the count`() {
        val adapter = RecordingAdapter()
        val focus = AssistantAudioFocus(adapter)

        focus.onTtsSentenceStarted()
        focus.onTtsSentenceStarted()
        focus.onTtsFlushed() // barge-in

        assertEquals(AssistantFocusState.IDLE, focus.state)
        assertEquals(1, adapter.abandons)

        // Late finishes from the flushed generation are no-ops.
        focus.onTtsSentenceFinished()
        focus.onTtsSentenceFinished()
        assertEquals(1, adapter.abandons)
        assertEquals(AssistantFocusState.IDLE, focus.state)
    }

    @Test
    fun `re-request works after a flush (barge-in regeneration)`() {
        val adapter = RecordingAdapter()
        val focus = AssistantAudioFocus(adapter)

        focus.onTtsSentenceStarted()
        focus.onTtsFlushed()
        focus.onTtsSentenceStarted() // regenerated answer

        assertEquals(AssistantFocusState.DUCKING, focus.state)
        assertEquals(2, adapter.requests)
        assertEquals(1, adapter.abandons)
    }

    @Test
    fun `denied focus keeps TTS alive without ducking`() {
        val adapter = RecordingAdapter(granted = false)
        val focus = AssistantAudioFocus(adapter)

        focus.onTtsSentenceStarted()
        assertEquals(AssistantFocusState.IDLE, focus.state) // no duck — but no crash
        focus.onTtsSentenceFinished()
        assertEquals(0, adapter.abandons) // nothing to abandon
    }

    @Test
    fun `spurious finish without a start is a no-op`() {
        val adapter = RecordingAdapter()
        val focus = AssistantAudioFocus(adapter)

        focus.onTtsSentenceFinished()
        assertEquals(0, adapter.abandons)
        assertEquals(AssistantFocusState.IDLE, focus.state)
    }

    @Test
    fun `double flush does not double-abandon`() {
        val adapter = RecordingAdapter()
        val focus = AssistantAudioFocus(adapter)

        focus.onTtsSentenceStarted()
        focus.onTtsFlushed()
        focus.onTtsFlushed()

        assertEquals(1, adapter.abandons)
        assertTrue(focus.state == AssistantFocusState.IDLE)
        assertFalse(focus.state == AssistantFocusState.DUCKING)
    }

    @Test
    fun `concurrent sentence lifecycle never wedges the machine`() {
        // B2: the mutators run on the multi-threaded AppGraph scope (up to 3
        // concurrent sentence coroutines). With an unsynchronized counter,
        // lost updates could leave activeSentences stuck >= 1 with no
        // sentence playing — DUCKING forever, external music permanently
        // ducked. Serialize the paired start/finish lifecycle from many
        // threads and assert the machine drains back to IDLE.
        val adapter = RecordingAdapter()
        val focus = AssistantAudioFocus(adapter)
        val threads = (1..8).map {
            Thread {
                repeat(100) {
                    focus.onTtsSentenceStarted()
                    focus.onTtsSentenceFinished()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        // After the storm the machine must be drained: back to IDLE (not
        // wedged in DUCKING with a phantom active sentence — the exact
        // failure mode an unsynchronized counter had) and every granted
        // request abandoned exactly once (no double-abandon, no leak).
        // Paired start/finish cycles legitimately re-request focus, so the
        // invariant is SYMMETRY, not a fixed count.
        assertEquals(AssistantFocusState.IDLE, focus.state)
        assertEquals(adapter.requests, adapter.abandons)
        assertTrue(adapter.requests >= 1)
    }
}
