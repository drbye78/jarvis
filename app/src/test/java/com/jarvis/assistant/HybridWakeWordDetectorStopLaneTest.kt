package com.jarvis.assistant

import com.jarvis.assistant.audio.HybridWakeWordDetector
import com.jarvis.assistant.audio.WakeWordEngine
import com.jarvis.assistant.contracts.DetectorState
import com.jarvis.assistant.contracts.WakeWordRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * COGNITIVE_PLAN 0.3: the dedicated stop lane (Porcupine primary) must
 * survive every engine×toggle combination and every arm-vs-swap ordering.
 * The re-audit found the arm decision inspected a stale primary while a
 * rebuild raced it — the lane was then never built and voice stop died
 * silently. The tail re-arm in [HybridWakeWordDetector.buildAndSwap] plus
 * the publish-time re-validation in buildStopLane close both directions of
 * that race; these tests pin them.
 */
class HybridWakeWordDetectorStopLaneTest {

    private fun req(engine: String, stop: Boolean) = WakeWordRequest(
        engine = engine,
        keywordPath = null,
        sherpaModelDir = null,
        sherpaCustomKeyword = "",
        sensitivity = 0.6f,
        stopPhraseEnabled = stop,
    )

    /** Porcupine-like primary: no stop phrase. */
    private fun wakeEngine(releases: AtomicInteger? = null) = object : WakeWordEngine {
        override val phrases = listOf(WakeWordEngine.Phrase(id = "jarvis", isStop = false))
        override fun process(chunk: ShortArray): Int = -1
        override fun release() {
            releases?.incrementAndGet()
        }
    }

    /** Sherpa-like primary WITH the stop phrase baked into the keyword set. */
    private fun sherpaWithStop() = object : WakeWordEngine {
        override val phrases = listOf(
            WakeWordEngine.Phrase(id = "jarvis", isStop = false),
            WakeWordEngine.Phrase(id = "stop", isStop = true),
        )
        override fun process(chunk: ShortArray): Int = -1
        override fun release() = Unit // fake: nothing native to free
    }

    /** Dedicated stop-lane engine. */
    private fun laneEngine(releases: AtomicInteger? = null) = object : WakeWordEngine {
        override val phrases = listOf(WakeWordEngine.Phrase(id = "stop", isStop = true))
        override fun process(chunk: ShortArray): Int = -1
        override fun release() {
            releases?.incrementAndGet()
        }
    }

    private fun detector(
        initial: WakeWordRequest,
        engineBuildDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        stopLaneFactory: (() -> WakeWordEngine)? = null,
    ): HybridWakeWordDetector = HybridWakeWordDetector(
        frames = emptyFlow(),
        context = null,
        initialReq = initial,
        engineFactory = { r ->
            if (r.engine == "sherpa") {
                if (r.stopPhraseEnabled) sherpaWithStop() else wakeEngine()
            } else {
                wakeEngine()
            }
        },
        engineBuildDispatcher = engineBuildDispatcher,
        stopLaneFactory = stopLaneFactory,
    )

    // ------------------------------------------------------------------
    // The 4 engine×toggle combos (the matrix from the re-audit)
    // ------------------------------------------------------------------

    @Test
    fun `combo sherpa + stopOn - primary covers stop, lane never built`() = runBlocking {
        val d = detector(req("sherpa", true), stopLaneFactory = { laneEngine() })
        try {
            d.setStopLaneEnabled(true)
            assertNull("primary owns the stop phrase — no dedicated lane", d.stopLaneForTest())
        } finally {
            d.release()
        }
    }

    @Test
    fun `combo porcupine + stopOn - armed lane is built`() = runBlocking {
        val d = detector(req("porcupine", true), stopLaneFactory = { laneEngine() })
        try {
            d.setStopLaneEnabled(true)
            assertNotNull("dedicated lane must be built for a stop-less primary", d.stopLaneForTest())
        } finally {
            d.release()
        }
    }

    @Test
    fun `combo porcupine + stopOff - arming is a no-op`() = runBlocking {
        val d = detector(req("porcupine", false), stopLaneFactory = { laneEngine() })
        try {
            d.setStopLaneEnabled(true)
            assertNull("stop disabled — no lane", d.stopLaneForTest())
        } finally {
            d.release()
        }
    }

    @Test
    fun `combo sherpa + stopOff - arming is a no-op`() = runBlocking {
        val d = detector(req("sherpa", false), stopLaneFactory = { laneEngine() })
        try {
            d.setStopLaneEnabled(true)
            assertNull("stop disabled — no lane even without a stop-capable primary", d.stopLaneForTest())
        } finally {
            d.release()
        }
    }

    // ------------------------------------------------------------------
    // The rebuild race (0.3): arm-vs-swap orderings
    // ------------------------------------------------------------------

    @Test
    fun `armed while sherpa covered stop, then swapped to porcupine - lane is rebuilt`() = runBlocking {
        val d = detector(req("sherpa", true), stopLaneFactory = { laneEngine() })
        try {
            d.setStopLaneEnabled(true)
            assertNull("sanity: covered by the primary", d.stopLaneForTest())

            // THE race: the toggle was armed against the old primary; the
            // swap to a stop-less primary must re-evaluate and build the lane.
            d.reconfigure(req("porcupine", true))
            assertNotNull(
                "swap tail must re-arm the stop lane — voice stop must survive the swap",
                d.stopLaneForTest(),
            )
        } finally {
            d.release()
        }
    }

    @Test
    fun `toggle flipped off mid-build - the built lane is dropped, not published`() = runBlocking {
        val gate = CountDownLatch(1)
        val laneReleases = AtomicInteger()
        val d = detector(
            req("porcupine", true),
            engineBuildDispatcher = Dispatchers.Default,
            stopLaneFactory = {
                gate.await(5, TimeUnit.SECONDS)
                laneEngine(laneReleases)
            },
        )
        try {
            withTimeout(5_000) { while (d.state.value != DetectorState.Ready) delay(10) }
            d.setStopLaneEnabled(true) // lane build parks on the gate
            delay(100) // let the arm launch park inside the factory
            d.setStopLaneEnabled(false) // user disables stop while the build runs

            gate.countDown() // build completes → publish must re-validate
            withTimeout(5_000) { while (laneReleases.get() == 0) delay(10) }
            assertNull("a lane made redundant by the toggle must be dropped", d.stopLaneForTest())
        } finally {
            d.release()
        }
    }

    @Test
    fun `lane exists, primary swaps to sherpa-with-stop - housekeeping releases the lane`() = runBlocking {
        val laneReleases = AtomicInteger()
        val d = detector(
            req("porcupine", true),
            engineBuildDispatcher = Dispatchers.Default,
            stopLaneFactory = { laneEngine(laneReleases) },
        )
        try {
            withTimeout(5_000) { while (d.state.value != DetectorState.Ready) delay(10) }
            d.setStopLaneEnabled(true)
            withTimeout(5_000) { while (d.stopLaneForTest() == null) delay(10) }

            d.reconfigure(req("sherpa", true))
            withTimeout(5_000) { while (laneReleases.get() == 0) delay(10) }
            assertNull("primary covers stop — the dedicated lane is dead weight", d.stopLaneForTest())
        } finally {
            d.release()
        }
    }
}
