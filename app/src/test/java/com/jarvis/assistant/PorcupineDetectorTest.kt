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
import java.util.concurrent.atomic.AtomicBoolean
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
            engineFactory = { req ->
                built.incrementAndGet()
                builtWith.add(req.sensitivity)
                engine
            },
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
            // Emit until the actor actually processes a frame: Ready only
            // guarantees the engine was PUBLISHED — the actor coroutine may
            // not have subscribed yet, and a MutableSharedFlow with no
            // subscriber drops the emission silently (observed flake).
            withTimeout(5_000) {
                while (!entered.await(10, TimeUnit.MILLISECONDS)) {
                    frames.emit(ShortArray(512))
                }
            }
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

    @Test
    fun `reconfigure degrades honestly when a native process wedges the mutex`() = runBlocking {
        // Bounded-reconfigure regression (audit fix): the publish step of
        // buildAndSwap used to wait on processMutex UNBOUNDED — a wedged
        // native process() hung every Settings-driven reconfigure forever.
        // Now the wait carries the release() deadline: reconfigure returns
        // promptly, keeps the OLD engine serving (state stays Ready), and
        // drops the freshly built engine instead of publishing it.
        val entered = CountDownLatch(1)
        val never = CountDownLatch(1)
        val wedgedReleased = AtomicInteger()
        val wedgedEngine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

            override fun process(chunk: ShortArray): Int {
                entered.countDown()
                never.await() // wedged "native" call — holds processMutex
                return -1
            }

            override fun release() { wedgedReleased.incrementAndGet() }
        }
        val built = AtomicInteger()
        val droppedBuilt = AtomicInteger()
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
            engineFactory = { _ ->
                val n = built.incrementAndGet()
                if (n == 1) {
                    wedgedEngine
                } else {
                    object : WakeWordEngine {
                        override val phrases: List<WakeWordEngine.Phrase> =
                            listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))
                        override fun process(chunk: ShortArray): Int = -1
                        override fun release() { droppedBuilt.incrementAndGet() }
                    }
                }
            },
        )

        runBlocking {
            withTimeout(5_000) {
                while (detector.state.value != DetectorState.Ready) delay(10)
            }
            // Emit until the actor is wedged inside process() (Ready alone
            // does not guarantee the actor has subscribed — see the
            // wedged-release test above).
            withTimeout(5_000) {
                while (!entered.await(10, TimeUnit.MILLISECONDS)) {
                    frames.emit(ShortArray(512))
                }
            }
        }

        // Reconfigure on its own thread: a regression (unbounded block)
        // surfaces as a failed assertion, not a hung test runner.
        val reconfigurer = Thread {
            kotlinx.coroutines.runBlocking {
                detector.setSensitivity(0.9f)
            }
        }
        reconfigurer.isDaemon = true
        val t0 = System.nanoTime()
        reconfigurer.start()
        reconfigurer.join(10_000)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        try {
            assertFalse("reconfigure hung on the wedged engine", reconfigurer.isAlive)
            // Bounded by the build + the 1.5 s engine-lock deadline.
            assertTrue("reconfigure took ${elapsedMs}ms", elapsedMs < 5_000)
            // The old engine is KEPT (never released while wedged — freeing a
            // native engine mid-process() is a use-after-free).
            assertEquals("wedged engine must NOT be released", 0, wedgedReleased.get())
            // The freshly built engine was dropped, not published.
            assertEquals("replacement engine must be dropped on timeout", 1, droppedBuilt.get())
            assertEquals("state stays Ready with the old engine", DetectorState.Ready, detector.state.value)
        } finally {
            never.countDown() // un-wedge for a clean exit
            detector.release()
        }
        // After the un-wedge, release() must free the still-serving engine.
        assertEquals(1, wedgedReleased.get())
    }

    @Test
    fun `no process runs on an engine after its release has returned - reconfigure race`() = runBlocking {
        // Stale-engine UAF regression (audit fix): the actor used to read the
        // `engine` field OUTSIDE processMutex and use that stale reference
        // inside the lock — a reconfigure publish (which releases the
        // displaced engine while holding the mutex) could free the engine
        // between the two reads, and the actor then called process() on
        // freed memory. The fake engines record the violation directly:
        // process() after release() == the use-after-free signature.
        val violation = AtomicBoolean()
        val frames = MutableSharedFlow<ShortArray>(extraBufferCapacity = 64)
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
            engineFactory = { _ ->
                object : WakeWordEngine {
                    val released = java.util.concurrent.atomic.AtomicBoolean(false)
                    override val phrases: List<WakeWordEngine.Phrase> =
                        listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))

                    override fun process(chunk: ShortArray): Int {
                        if (released.get()) violation.set(true)
                        return -1
                    }

                    override fun release() { released.set(true) }
                }
            },
            // Real dispatcher (NOT the Unconfined unit-test default): this
            // test exercises genuine actor/publisher interleaving — with
            // Unconfined both run inline on the emitter thread and the race
            // could never manifest.
            engineBuildDispatcher = Dispatchers.Default,
        )

        withTimeout(5_000) {
            while (detector.state.value != DetectorState.Ready) delay(10)
        }

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = launch(Dispatchers.Default) {
            while (!stop.get()) {
                frames.emit(ShortArray(512))
                delay(1)
            }
        }
        // Sensitivity-drag storm: 50 live reconfigures while audio flows.
        repeat(50) { i ->
            detector.setSensitivity(0.5f + i / 100f)
            delay(2)
        }
        stop.set(true)
        writer.join()
        detector.release()

        assertFalse(
            "process() ran on a released engine — stale-field use-after-free",
            violation.get(),
        )
    }

    @Test
    fun `initial publish timeout surfaces Failed and DetectorError instead of stuck Bootstrapping`() = runBlocking {
        // F-A regression: a publish-timeout during the FIRST build (nothing
        // published yet) used to leave the detector in Bootstrapping — deaf
        // while reporting "still starting", with no Failed reason for the
        // session layer and no DetectorError event. The production
        // post-publish-failure handler (onPublishFailed) now mirrors the
        // build-failure branch.
        //
        // Driven via the production handler directly (not end-to-end)
        // because the engine==null + Bootstrapping + mutex-timeout
        // combination is unreachable through the public API: every
        // long-term processMutex holder (the frame actor) only exists
        // after a successful publish, at which point engine != null. The
        // end-to-end timeout wiring (engine != null, keep-old-engine) is
        // pinned by the wedged-reconfigure test above.
        val buildGate = CountDownLatch(1) // park the initial build: state stays Bootstrapping
        val engine = object : WakeWordEngine {
            override val phrases: List<WakeWordEngine.Phrase> =
                listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))
            override fun process(chunk: ShortArray): Int = -1
            override fun release() {}
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
            engineFactory = {
                buildGate.await(10, TimeUnit.SECONDS)
                engine
            },
            engineBuildDispatcher = Dispatchers.Default,
        )

        try {
            // The initial build is parked mid-flight: nothing published yet.
            assertEquals(DetectorState.Bootstrapping, detector.state.value)

            val errors = CopyOnWriteArrayList<String>()
            val collector = launch(Dispatchers.Default) {
                detector.detections().collect {
                    if (it is Detection.DetectorError) errors.add(it.message)
                }
            }
            val detections =
                detector.detections() as kotlinx.coroutines.flow.MutableSharedFlow<Detection>
            withTimeout(5_000) {
                while (detections.subscriptionCount.value == 0) delay(10)
            }

            // The production post-publish-failure path: the initial build
            // produced an engine but the publish timed out (nothing
            // published, state still Bootstrapping).
            detector.onPublishFailed(built = engine, timedOut = true)

            val s = detector.state.value
            assertTrue("expected Failed, was $s", s is DetectorState.Failed)
            assertTrue(
                "Failed reason must name the publish timeout: ${(s as DetectorState.Failed).reason}",
                s.reason.contains("publish timed out"),
            )
            withTimeout(5_000) { while (errors.isEmpty()) delay(10) }
            assertTrue(
                "DetectorError must be surfaced through the event flow",
                errors.single().contains("publish timed out"),
            )
            collector.cancel() // infinite collector — runBlocking waits for it
            collector.join()
        } finally {
            buildGate.countDown() // let the parked initial build finish
            detector.release()
        }
    }
}
