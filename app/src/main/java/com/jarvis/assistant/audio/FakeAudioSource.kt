package com.jarvis.assistant.audio

import com.jarvis.assistant.contracts.AudioSource
import com.jarvis.assistant.contracts.AudioSpec
import java.util.ArrayDeque

/**
 * Test / emulator [AudioSource] that emits a configurable, repeating frame at
 * the mic sample rate instead of touching the microphone.
 *
 * By default it emits silence; callers may supply a tone or any pattern via
 * [repeatingFrame], or push one-off frames with [pushFrame] (useful for
 * deterministic tests).
 */
class FakeAudioSource(
    private val spec: AudioSpec = AudioSpec.MIC,
    repeatingFrame: ShortArray = ShortArray((spec.sampleRate * 20) / 1000)
) : AudioSource {

    private val frameDurationMs = (repeatingFrame.size * 1000L) / spec.sampleRate
    private val lock = Any()
    private var repeating: ShortArray = repeatingFrame
    private val pushed = ArrayDeque<ShortArray>()
    @Volatile private var running = false

    fun setRepeatingFrame(frame: ShortArray) = synchronized(lock) { repeating = frame }

    /** Queue a one-off frame that will be returned before the repeating frame. */
    fun pushFrame(frame: ShortArray) = synchronized(lock) { pushed.addLast(frame) }

    override fun start() {
        running = true
    }

    override fun read(): ShortArray {
        if (!running) return ShortArray(0)
        val frame = synchronized(lock) {
            if (pushed.isNotEmpty()) pushed.removeFirst() else repeating.copyOf(repeating.size)
        }
        // Block ~one frame duration so downstream timing resembles real capture.
        if (frameDurationMs > 0) Thread.sleep(frameDurationMs)
        return frame
    }

    override fun stop() {
        running = false
        synchronized(lock) { pushed.clear() }
    }
}
