package com.jarvis.assistant

import com.jarvis.assistant.audio.HybridWakeWordDetector
import com.jarvis.assistant.audio.WakeWordEngine
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JVM tests for [HybridWakeWordDetector] using an injected fake [WakeWordEngine]:
 * init-failure surfacing (M1) and teardown ordering (C3 release race).
 */
class PorcupineDetectorTest {

    @Test
    fun `init failure surfaces as Failed state with reason`() {
        val detector = HybridWakeWordDetector(
            frames = emptyFlow(),
            context = null,
            initialReq = WakeWordRequest(
                engine = "porcupine",
                keywordPath = "missing.ppn",
                sherpaModelDir = null,
                sherpaCustomKeyword = "",
                sensitivity = 0.6f,
            ),
            engineFactory = { _ -> throw IllegalStateException("native boom") },
            engineBuildDispatcher = Dispatchers.Unconfined,
        )
        val s = detector.state.value
        assertTrue("expected Failed but was $s", s is DetectorState.Failed)
        assertTrue((s as DetectorState.Failed).reason.isNotBlank())
        detector.release() // must be safe on a failed detector
    }

    @Test
    fun `release joins in-flight process before deleting engine`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val processStarted = CountDownLatch(1)

        val engine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

            override fun process(chunk: ShortArray): Int {
                events.add("process")
                processStarted.countDown()
                Thread.sleep(200) // simulate native work in flight
                return -1
            }

            override fun release() {
                events.add("delete")
            }
        }

        // One 512-sample frame to trigger exactly one process() call, then
        // the flow stays alive so the actor is still running at release().
        val frames = flow {
            emit(ShortArray(512))
            awaitCancellation()
        }

        val detector = HybridWakeWordDetector(
            frames = frames,
            context = null,
            initialReq = WakeWordRequest(
                engine = "porcupine",
                keywordPath = "kw.ppn",
                sherpaModelDir = null,
                sherpaCustomKeyword = "",
                sensitivity = 0.6f,
            ),
            engineFactory = { _ -> engine },
            engineBuildDispatcher = Dispatchers.Unconfined,
        )

        assertTrue(processStarted.await(2, TimeUnit.SECONDS))

        val t0 = System.nanoTime()
        detector.release()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        // The documented join budget is 1 s: release must stay well under it
        // even with a ~200 ms process() in flight (a dead join would either
        // race delete ahead of the actor or block unbounded on the mutex).
        assertTrue("release() took ${elapsedMs}ms, expected < 1000ms", elapsedMs < 1_000)
        // Ordering invariant: the in-flight process completed BEFORE delete,
        // and no process() may ever run after the engine was freed.
        assertEquals(listOf("process", "delete"), events)
    }

    @Test
    fun `double release is idempotent and prompt`() {
        val deletes = AtomicInteger()
        val engine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

            override fun process(chunk: ShortArray): Int = -1
            override fun release() {
                deletes.incrementAndGet()
            }
        }
        val detector = HybridWakeWordDetector(
            frames = emptyFlow(),
            context = null,
            initialReq = WakeWordRequest(
                engine = "porcupine",
                keywordPath = "kw.ppn",
                sherpaModelDir = null,
                sherpaCustomKeyword = "",
                sensitivity = 0.6f,
            ),
            engineFactory = { _ -> engine },
            engineBuildDispatcher = Dispatchers.Unconfined,
        )

        val t0 = System.nanoTime()
        detector.release()
        detector.release() // second call must be a safe no-op
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue("double release() took ${elapsedMs}ms", elapsedMs < 2_000)
        assertEquals(DetectorState.Released, detector.state.value)
        assertEquals(1, deletes.get()) // engine freed exactly once
    }

    @Test
    fun `runtime process failure surfaces as Failed and DetectorError`() = runBlocking {
        var calls = 0
        val engine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

            override fun process(chunk: ShortArray): Int {
                calls++
                if (calls >= 2) throw IllegalStateException("native exploded")
                return -1
            }

            override fun release() {}
        }
        val frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 16)
        val detector = HybridWakeWordDetector(
            frames = frames,
            context = null,
            initialReq = WakeWordRequest(
                engine = "porcupine",
                keywordPath = "kw.ppn",
                sherpaModelDir = null,
                sherpaCustomKeyword = "",
                sensitivity = 0.6f,
            ),
            engineFactory = { _ -> engine },
            engineBuildDispatcher = Dispatchers.Unconfined,
        )

        // Subscribe BEFORE the failure so the DetectorError emission is seen.
        val errors = CopyOnWriteArrayList<String>()
        val collector = launch(Dispatchers.Default) {
            detector.detections().collect {
                if (it is Detection.DetectorError) errors.add(it.message)
            }
        }
        // The detector backs detections() with a SharedFlow; use its
        // subscriptionCount to know the collector has attached.
        val detections =
            detector.detections() as kotlinx.coroutines.flow.MutableSharedFlow<Detection>
        withTimeout(5_000) {
            while (detections.subscriptionCount.value == 0) delay(10)
        }

        frames.emit(ShortArray(512)) // call 1: fine
        frames.emit(ShortArray(512)) // call 2: throws inside the actor

        withTimeout(5_000) {
            while (detector.state.value !is DetectorState.Failed) delay(20)
        }
        assertTrue(errors.isNotEmpty())
        detector.release()
        collector.cancel()
    }

    @Test
    fun `setSensitivity rebuilds engine and releases previous`() {
        val built = AtomicInteger()
        val builtWith = CopyOnWriteArrayList<Float>()
        val released = AtomicInteger()
        val engine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

            override fun process(chunk: ShortArray): Int = -1
            override fun release() { released.incrementAndGet() }
        }
        val detector = HybridWakeWordDetector(
            frames = emptyFlow(),
            context = null,
            initialReq = WakeWordRequest(
                engine = "porcupine",
                keywordPath = null,
                sherpaModelDir = null,
                sherpaCustomKeyword = "",
                sensitivity = 0.6f,
            ),
            engineFactory = { req -> built.incrementAndGet(); builtWith.add(req.sensitivity); engine },
            engineBuildDispatcher = Dispatchers.Unconfined,
        )
        assertEquals(1, built.get()) // initial build at 0.6f
        runBlocking { detector.setSensitivity(0.9f) }
        assertEquals(2, built.get())
        assertEquals(0.9f, builtWith[1])
        assertEquals(1, released.get()) // previous engine released on swap
        detector.release()
        assertEquals(2, released.get()) // engine freed exactly once more
    }

    @Test
    fun `release returns promptly when a native process wedges forever`() {
        // Audit #1 regression: a native process() stuck past the join budget
        // holds processMutex; the old unbounded withLock inside runBlocking
        // then blocked the releasing thread FOREVER (ANR on shutdown). The
        // mutex acquisition is now deadline-bounded — release() must return
        // and report Released, deliberately leaking the wedged engine.
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val engine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

            override fun process(chunk: ShortArray): Int {
                entered.countDown()
                never.await() // wedged "native" call — never returns
                return -1
            }

            override fun release() {} // must never be reached (use-after-free)
        }

        // Real dispatcher: with Unconfined the actor would run the blocking
        // process() on THIS thread and the emit below would never return.
        val frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 16)
        val detector = HybridWakeWordDetector(
            frames = frames,
            context = null,
            initialReq = WakeWordRequest(
                engine = "porcupine",
                keywordPath = "kw.ppn",
                sherpaModelDir = null,
                sherpaCustomKeyword = "",
                sensitivity = 0.6f,
            ),
            engineFactory = { _ -> engine },
        )

        // Wait for the engine to be live, then wedge it inside process().
        runBlocking {
            withTimeout(5_000) {
                while (detector.state.value != DetectorState.Ready) delay(10)
            }
            frames.emit(ShortArray(512))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        }

        // Run release() on its own thread so a regression (unbounded block)
        // surfaces as a FAILED assertion instead of a hung test runner.
        val releaser = Thread { detector.release() }
        releaser.isDaemon = true
        val t0 = System.nanoTime()
        releaser.start()
        try {
            releaser.join(10_000)
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            assertFalse("release() hung on a wedged engine", releaser.isAlive)
            // Bounded by join (1 s) + engine-lock (1.5 s) budgets.
            assertTrue("release() took ${elapsedMs}ms", elapsedMs < 6_000)
            assertEquals(DetectorState.Released, detector.state.value)
        } finally {
            never.countDown() // un-wedge the daemon threads for a clean exit
        }
    }
}
