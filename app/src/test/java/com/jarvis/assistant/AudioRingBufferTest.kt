package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.audio.AudioRingBuffer
import com.jarvis.assistant.contracts.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for M8 (pre-roll sizing) and the eviction diagnostics: ring
 * capacity derives from preRollMs and unread-frame evictions are counted.
 */
class AudioRingBufferTest {

    private class NoopSource : AudioSource {
        override fun start() {}
        override fun stop() {}
        override fun read(): ShortArray = ShortArray(0)
    }

    /** Emits 320-sample frames until [maxFrames], then reports closed. */
    private class PumpSource(private val maxFrames: Int = Int.MAX_VALUE) : AudioSource {
        val reads = AtomicInteger()
        override fun start() {}
        override fun stop() {}
        override fun read(): ShortArray {
            if (reads.incrementAndGet() > maxFrames) {
                throw IllegalStateException("AudioRecordSource not started")
            }
            return ShortArray(320)
        }
    }

    private class RecordingTree : Timber.Tree() {
        val lines = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines.add(priority to message)
        }
    }

    private lateinit var scope: CoroutineScope
    private val trees = mutableListOf<RecordingTree>()

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        trees.forEach { Timber.uproot(it) }
    }

    private fun plant(tree: RecordingTree): RecordingTree {
        Timber.plant(tree)
        trees.add(tree)
        return tree
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            delay(10)
        }
    }

    // --- M8: capacity honors preRollMs ---

    @Test
    fun `ring capacity derives from preRollMs at 20ms frames`() {
        assertEquals(150, AudioPipeline.ringCapacity(3_000)) // default ≈ 96 KB
        assertEquals(2, AudioPipeline.ringCapacity(40))
        assertEquals(1, AudioPipeline.ringCapacity(FRAME_MS_SINGLE))
        // Degenerate config values still keep one frame of headroom.
        assertEquals(1, AudioPipeline.ringCapacity(0))
        assertEquals(1, AudioPipeline.ringCapacity(-5))
    }

    @Test
    fun `pipeline ring buffer uses configured preRollMs`() {
        val p = AudioPipeline(scope, NoopSource(), preRollMs = 3_000)
        assertEquals(150, p.ringBuffer.capacity)
        val small = AudioPipeline(scope, NoopSource(), preRollMs = 40)
        assertEquals(2, small.ringBuffer.capacity)
        p.release()
        small.release()
    }

    @Test
    fun `pipeline default constructor matches JarvisConfig pre-roll default`() {
        val config = com.jarvis.assistant.config.JarvisConfig()
        val p = AudioPipeline(scope, NoopSource())
        assertEquals(
            config.preRollMs / AudioPipeline.FRAME_MS,
            p.ringBuffer.capacity.toLong(),
        )
        p.release()
    }

    // --- Eviction counter ---

    @Test
    fun `eviction counter increments when buffer overflows`() {
        val ring = AudioRingBuffer(capacity = 2)
        ring.add(frame(0)); ring.add(frame(1))
        assertEquals("no evictions while under capacity", 0L, ring.evictionCount)
        ring.add(frame(2))
        assertEquals(1L, ring.evictionCount)
        ring.add(frame(3))
        assertEquals(2L, ring.evictionCount)

        // Oldest frames were dropped; drain returns only survivors.
        val drained = ring.drain()
        assertEquals(2, drained.size)
        assertTrue(drained.all { it.size == 320 })
        // Drain clears contents but the cumulative counter persists.
        assertEquals(2L, ring.evictionCount)
        assertEquals(0, ring.drain().size)
    }

    @Test
    fun `producer logs eviction counter when unread pre-roll frames are evicted`() = runBlocking {
        val tree = plant(RecordingTree())
        val source = PumpSource(maxFrames = 200)
        // Capacity 2 → every frame past the first two evicts one predecessor.
        val p = AudioPipeline(scope, source, preRollMs = 40)
        p.start()
        try {
            awaitUntil { p.ringBuffer.evictionCount >= 5 }
            p.stop()
            val overflowLines = tree.lines.filter { it.second.contains("Pre-roll overflow") }
            assertTrue(
                "expected an eviction log line, got ${tree.lines}",
                overflowLines.isNotEmpty(),
            )
            assertTrue(overflowLines.first().second.contains("evicted"))
        } finally {
            p.release()
        }
    }

    private companion object {
        const val FRAME_MS_SINGLE = 20L
        fun frame(id: Int): ShortArray = ShortArray(320).also { it[0] = id.toShort() }
    }
}
