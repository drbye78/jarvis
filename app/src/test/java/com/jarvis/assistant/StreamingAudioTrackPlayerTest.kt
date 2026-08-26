package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioTrackAdapter
import com.jarvis.assistant.audio.StreamingAudioTrackPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Player actor tests via a fake [AudioTrackAdapter] (m4): flush generation
 * semantics preserved, release fails queued deferreds promptly, short writes
 * abort the sentence with a log, and hardware control is routed as actor
 * commands.
 */
class StreamingAudioTrackPlayerTest {

    /** Records every call; can simulate blocking writes and short-write failures. */
    private class FakeAdapter(
        @Volatile var writeBlockMs: Long = 0,
        @Volatile var shortWriteAtCall: Int = Int.MAX_VALUE,
    ) : AudioTrackAdapter {
        val events = Collections.synchronizedList(mutableListOf<String>())
        private val writeCalls = AtomicInteger()
        private val writtenBytes = ConcurrentHashMap<Int, AtomicInteger>()

        fun writesOf(chunkSize: Int): Int = writtenBytes[chunkSize]?.get() ?: 0
        fun totalWrites(): Int = writtenBytes.values.sumOf { it.get() }
        fun count(event: String): Int = events.count { it == event }

        override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            events.add("write:$sizeInBytes")
            if (writeCalls.incrementAndGet() == shortWriteAtCall) return 0
            if (writeBlockMs > 0) Thread.sleep(writeBlockMs)
            writtenBytes.computeIfAbsent(sizeInBytes) { AtomicInteger() }.incrementAndGet()
            return sizeInBytes
        }

        override fun play() { events.add("play") }
        override fun pause() { events.add("pause") }
        override fun flush() { events.add("flush") }
        override fun stop() { events.add("stop") }
        override fun release() { events.add("release") }
    }

    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun player(adapter: FakeAdapter) =
        StreamingAudioTrackPlayer(scope, adapter = adapter)

    private fun pcm(chunkSize: Int, chunks: Int): Flow<ByteArray> = flow {
        repeat(chunks) { emit(ByteArray(chunkSize)) }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            delay(10)
        }
    }

    /** Awaits a Deferred and reports whether it completed EXCEPTIONALLY. */
    private suspend fun failedWithin(deferred: CompletableDeferred<Unit>, timeoutMs: Long = 2_000): Boolean =
        withTimeout(timeoutMs) { runCatching { deferred.await() }.isFailure }

    @Test
    fun `flush drops current and queued sentences and preserves generation semantics`() = runBlocking {
        // Slow writes keep the first sentence "playing" while we enqueue more.
        val adapter = FakeAdapter(writeBlockMs = 25)
        val p = player(adapter)

        val current = p.play(pcm(chunkSize = 100, chunks = 8))
        awaitUntil { adapter.writesOf(100) >= 2 } // actively writing sentence 1

        val queued = p.play(pcm(chunkSize = 200, chunks = 4))
        p.flush()

        assertTrue("current sentence must settle", failedWithin(current))
        assertTrue("queued sentence must settle", failedWithin(queued))

        // The queued sentence never wrote a single byte.
        assertEquals("queued sentence leaked writes", 0, adapter.writesOf(200))

        // Flush was routed through the ACTOR as pause+flush of the track.
        awaitUntil { adapter.count("flush") >= 1 && adapter.count("pause") >= 1 }

        // Generation semantics preserved: playback after flush works normally.
        val fresh = p.play(pcm(chunkSize = 300, chunks = 2))
        withTimeout(5_000) { fresh.await() } // completes NORMALLY
        assertEquals(2, adapter.writesOf(300))

        p.release()
    }

    @Test
    fun `release completes queued sentence deferreds exceptionally and promptly`() = runBlocking {
        val adapter = FakeAdapter(writeBlockMs = 50)
        val p = player(adapter)

        val sentences = (1..4).map { idx -> p.play(pcm(chunkSize = 100 * idx, chunks = 20)) }
        awaitUntil { adapter.totalWrites() >= 2 } // s1 playing; s2..s4 queued

        val t0 = System.nanoTime()
        p.release()

        sentences.forEachIndexed { idx, done ->
            assertTrue(
                "sentence ${idx + 1} did not complete exceptionally",
                failedWithin(done),
            )
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("release settling took ${elapsedMs}ms", elapsedMs < 1_500)

        // Hardware release is an ACTOR COMMAND — wait for the actor to have
        // processed it before asserting on the adapter call log.
        awaitUntil { adapter.count("release") >= 1 }
        assertEquals(1, adapter.count("release"))
        assertEquals(1, adapter.count("stop"))

        // play() after release fails immediately instead of hanging.
        assertTrue(failedWithin(p.play(pcm(100, 1)), timeoutMs = 1_000))
    }

    @Test
    fun `short write aborts sentence with error log and actor survives`() = runBlocking {
        val recording = RecordingTree().also { Timber.plant(it) }
        try {
            val adapter = FakeAdapter(shortWriteAtCall = 2) // 2nd framework write fails
            val p = player(adapter)

            val aborted = p.play(pcm(chunkSize = 100, chunks = 5))
            assertTrue(
                "short write must abort the sentence exceptionally",
                failedWithin(aborted),
            )
            val cause = runCatching { aborted.await() }
                .exceptionOrNull()
            assertTrue(cause is IllegalStateException)
            assertTrue(
                "error should mention the short write: $cause",
                cause?.message?.contains("short write") == true,
            )

            // Error was LOGGED, not silent (Timber ERROR priority == 6).
            assertTrue(
                "expected an error log for the aborted sentence, got ${recording.lines}",
                recording.lines.any { it.first == 6 && it.second.contains("aborted") },
            )

            // The actor loop survives the aborted sentence.
            val next = p.play(pcm(chunkSize = 200, chunks = 3))
            withTimeout(5_000) { next.await() }
            assertEquals(3, adapter.writesOf(200))

            p.release()
        } finally {
            Timber.uproot(recording)
        }
    }

    @Test
    fun `double release is idempotent`() = runBlocking {
        // Slow writes guarantee the sentence is still in flight at release().
        val adapter = FakeAdapter(writeBlockMs = 20)
        val p = player(adapter)
        val done = p.play(pcm(100, 20))
        p.release()
        p.release() // second call must be a safe no-op
        assertTrue(failedWithin(done))
        awaitUntil { adapter.count("release") >= 1 } // actor processed the command
        assertEquals("hardware released exactly once", 1, adapter.count("release"))
    }

    private class RecordingTree : Timber.Tree() {
        val lines = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines.add(priority to message)
        }
    }
}
