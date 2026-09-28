package com.jarvis.assistant.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DEVICE-ONLY proof that the wake word is actually DETECTED end to end.
 *
 * [SherpaSmokeTest] deliberately stops at "constructs without crashing and
 * returns -1 on silence" and states that "keyword DETECTION accuracy (audio in
 * → matched id) is not asserted here". That gap is exactly where the real bug
 * lived: the native spotter matched the phrase, but [SherpaKwsEngine.matchEntry]
 * compared the DETOKENIZED native result (`"JARVIS"`) against the raw BPE token
 * line (`"▁JA R VI S"`), so every detection was silently discarded and the
 * assistant never woke — with no error anywhere. Every other test in the repo
 * passed while the assistant was completely deaf.
 *
 * These tests drive REAL 16 kHz PCM through the engine and assert the matched
 * phrase id, which is the only thing that distinguishes "deaf" from "listening".
 *
 * **ONE test, ONE engine build, deliberately.** Building a second sherpa engine
 * in the same instrumented process wedges the device (a pre-existing hazard:
 * [SherpaSmokeTest]'s full-class run hangs the same way, while every one of its
 * tests passes when run alone). Keeping a single engine for the whole class
 * sidesteps it and still covers all three directions. Do NOT split this back
 * into several @Test methods that each build an engine.
 *
 * The fixtures are synthesized speech (piper), not recordings of the owner, so
 * they prove the MATCHING path, not this speaker's acoustic recall — the
 * English-only gigaspeech model's behaviour on a Russian-accented «Джарвис»
 * remains a hardware/pronunciation question.
 */
@RunWith(AndroidJUnit4::class)
class SherpaDetectionTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun bundledEngine_detectsWakeAndStopFromPcm_neverWakeOnSilence() {
        val engine = SherpaKwsEngine(
            context = context,
            sensitivity = 0.6f,
            entries = listOf(SherpaKeywords.wake(), SherpaKeywords.stop()),
        )
        try {
            // 1. The wake phrase must DETECT (index 0). This is the regression
            //    guard: before the fix this returned -1 for real speech.
            val wake = detect(engine, "wake/jarvis_16k.wav")
            assertTrue(
                "the bundled engine must DETECT 'Jarvis' in the fixture — a missing hit here " +
                    "is the silent-deaf regression (the native result is DETOKENIZED and must " +
                    "be matched via SherpaKwsEngine.normalizeKeyword)",
                wake.contains(0),
            )

            // 2. The stop phrase must DETECT and map to the stop entry (index 1),
            //    never onto the wake id.
            val stop = detect(engine, "wake/stop_16k.wav")
            assertTrue("the bundled engine must DETECT the stop phrase", stop.contains(1))

            // 3. Detection must survive a second utterance on the same engine
            //    (the detector reuses one engine for the whole session).
            val wakeAgain = detect(engine, "wake/jarvis_16k.wav")
            assertTrue("the engine must keep detecting after a stop hit", wakeAgain.contains(0))

            // 4. And silence must never be reported as a keyword — the fix must
            //    not trade deafness for false wakes.
            assertEquals(
                "silence must never produce a keyword",
                -1,
                engine.process(ShortArray(16_000)),
            )
        } finally {
            engine.release()
        }
    }

    /**
     * Feeds a whole WAV fixture through the engine in the same 512-sample chunks
     * the detector actor uses, returning every matched phrase index.
     */
    private fun detect(engine: WakeWordEngine, asset: String): List<Int> {
        val samples = readPcm16Asset(asset)
        val hits = mutableListOf<Int>()
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + CHUNK, samples.size)
            val index = engine.process(samples.copyOfRange(offset, end))
            if (index >= 0) hits.add(index)
            offset = end
        }
        return hits
    }

    /** Minimal PCM-16 WAV reader: walks chunks and returns little-endian samples. */
    private fun readPcm16Asset(path: String): ShortArray {
        val bytes = InstrumentationRegistry.getInstrumentation()
            .context.assets.open(path).use { it.readBytes() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12 // skip "RIFF", size, "WAVE"
        var dataOffset = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = buf.getInt(pos + 4)
            if (id == "data") {
                dataOffset = pos + 8
                dataLen = minOf(size, bytes.size - dataOffset)
                break
            }
            pos += 8 + size + (size and 1) // chunks are word-aligned
        }
        assertTrue("fixture '$path' has no PCM data chunk", dataOffset >= 0)
        val out = ShortArray(dataLen / 2)
        buf.asShortBuffer().apply { position(dataOffset / 2) }.get(out)
        return out
    }

    private companion object {
        /** The detector actor's native chunk size (see HybridWakeWordDetector). */
        const val CHUNK = 512
    }
}
