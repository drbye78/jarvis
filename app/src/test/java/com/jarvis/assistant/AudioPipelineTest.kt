package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioPipeline
import com.jarvis.assistant.contracts.AudioSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for the m5 producer lifecycle: a source reporting itself
 * not-started/closed ([IllegalStateException] from read) makes the producer
 * exit cleanly with exactly one log line instead of spamming retry delays,
 * and [AudioPipeline.start] revives it.
 */
class AudioPipelineTest {

    /** Source that is broken until flipped healthy; counts every read attempt. */
    private class FlakySource : AudioSource {
        val reads = AtomicInteger()
        val healthy = AtomicBoolean(false)
        override fun start() {}
        override fun stop() {}
        override fun read(): ShortArray {
            reads.incrementAndGet()
            if (!healthy.get()) throw IllegalStateException("AudioRecordSource not started")
            return ShortArray(320)
        }
    }

    /** Source that always fails with a non-lifecycle error. */
    private class BrokenSource : AudioSource {
        val reads = AtomicInteger()
        override fun start() {}
        override fun stop() {}
        override fun read(): ShortArray {
            reads.incrementAndGet()
            throw java.io.IOException("transient hardware hiccup")
        }
    }

    private class RecordingTree : Timber.Tree() {
        val lines = Collections.synchronizedList(mutableListOf<Pair<Int, String>>())
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            lines.add(priority to message)
        }
    }

    private lateinit var scope: CoroutineScope
    private var tree: RecordingTree? = null

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        tree?.let { Timber.uproot(it) }
    }

    private suspend fun awaitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) error("condition not met within ${timeoutMs}ms")
            delay(10)
        }
    }

    @Test
    fun `producer exits cleanly with single log line when source reports closed`() = runBlocking {
        val recording = RecordingTree().also { tree = it; Timber.plant(it) }
        val source = FlakySource()
        val p = AudioPipeline(scope, source, preRollMs = 1_000)

        p.start()
        awaitUntil { !p.isRunning() } // clean exit flips running off

        assertTrue(
            "source must have been read at least once (reads=${source.reads.get()})",
            source.reads.get() >= 1,
        )

        // Exactly ONE log line about the exit — not the old delay(100) spam.
        val exitLines = recording.lines.filter { it.second.contains("exiting cleanly") }
        assertEquals(
            "expected exactly one clean-exit line, captured: ${recording.lines}",
            1,
            exitLines.size,
        )

        // The producer actually stopped reading.
        val frozenReads = source.reads.get()
        delay(200)
        assertEquals("read attempts must stop after clean exit", frozenReads, source.reads.get())

        p.release()
    }

    @Test
    fun `start revives producer after clean exit on unavailable source`() = runBlocking {
        val source = FlakySource()
        val p = AudioPipeline(scope, source, preRollMs = 1_000)

        p.start()
        awaitUntil { !p.isRunning() }

        val received = AtomicInteger()
        val collector = scope.launch { p.frames.collect { received.incrementAndGet() } }
        source.healthy.set(true)
        p.start() // revive

        awaitUntil(timeoutMs = 5_000) { received.get() >= 3 }

        p.release()
        collector.cancel()
        assertTrue("revived producer must deliver frames", received.get() >= 3)
    }

    @Test
    fun `generic read errors keep producer alive with retry backoff`() = runBlocking {
        val source = BrokenSource()
        val p = AudioPipeline(scope, source, preRollMs = 1_000)

        p.start()
        // IOException path retries (READ_RETRY_DELAY_MS); pipeline stays up.
        awaitUntil { source.reads.get() >= 3 }
        assertTrue(p.isRunning())
        assertTrue(!p.hasGivenUp())

        p.release()
    }

    @Test
    fun `producer gives up after repeated failures and reports it honestly`() = runBlocking {
        // Audit #25: after GIVE_UP_AFTER_CONSECUTIVE_FAILURES consecutive
        // non-lifecycle failures the producer must exit with running=false
        // AND a distinct gave-up flag (so the service watchdog can revive it
        // later); it must actually STOP reading instead of spinning forever.
        val source = RetryThenHealSource()
        val p = AudioPipeline(scope, source, preRollMs = 1_000)

        p.start()
        awaitUntil(timeoutMs = 20_000) { p.hasGivenUp() && !p.isRunning() }

        // Reads stopped at the give-up point (50 + a bounded overshoot).
        val frozen = source.reads.get()
        delay(250)
        assertEquals("reads must stop after give-up", frozen, source.reads.get())
        assertTrue("gave up around 50 reads, was ${source.reads.get()}", source.reads.get() in 50..65)

        p.release()
    }

    @Test
    fun `start revives a pipeline that gave up`() = runBlocking {
        val source = RetryThenHealSource()
        val p = AudioPipeline(scope, source, preRollMs = 1_000)

        p.start()
        awaitUntil(timeoutMs = 20_000) { p.hasGivenUp() }

        val received = AtomicInteger()
        val collector = scope.launch { p.frames.collect { received.incrementAndGet() } }
        source.healthy.set(true)
        p.start() // watchdog-style revive

        awaitUntil(timeoutMs = 5_000) { received.get() >= 3 }
        assertTrue("revived producer must deliver frames", received.get() >= 3)
        assertFalse("successful start clears the give-up flag", p.hasGivenUp())
        assertTrue(p.isRunning())

        p.release()
        collector.cancel()
    }
}

/** Fails with a non-lifecycle error until flipped healthy (give-up path). */
private class RetryThenHealSource : AudioSource {
    val reads = AtomicInteger()
    val healthy = AtomicBoolean(false)
    override fun start() {}
    override fun stop() {}
    override fun read(): ShortArray {
        reads.incrementAndGet()
        if (!healthy.get()) throw java.io.IOException("hardware glitch")
        return ShortArray(320)
    }
}
