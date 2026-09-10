package com.jarvis.assistant.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Graph-ready gating (bootstrap-vs-stopped honesty): the Settings buttons
 * and the home toggle must distinguish "still bootstrapping" from "service
 * stopped" instead of reading both as «Запустите ассистента». Pure JVM —
 * the helper takes plain parameters, no Android graph required.
 */
class GraphReadyGateTest {

    @Test
    fun `a graph that is already up returns immediately`() = runBlocking {
        val graph = Any()
        val outcome = awaitGraphReady(
            graphNow = graph,
            ready = null, // would mean "no service" — must not be consulted
            serviceAlive = false,
            timeoutMs = 100,
        )
        assertTrue(outcome is GraphReadyOutcome.Ready)
        assertEquals(graph, (outcome as GraphReadyOutcome.Ready<*>).graph)
    }

    @Test
    fun `no service attached reads as stopped`() = runBlocking {
        val outcome = awaitGraphReady(
            graphNow = null,
            ready = null,
            serviceAlive = false,
            timeoutMs = 100,
        )
        assertEquals(GraphReadyOutcome.Stopped, outcome)
    }

    @Test
    fun `a completed deferred resolves without waiting`() = runBlocking {
        val ready = CompletableDeferred(Any())
        val outcome = awaitGraphReady(
            graphNow = null,
            ready = ready,
            serviceAlive = true,
            timeoutMs = 5_000,
        )
        assertTrue(outcome is GraphReadyOutcome.Ready)
    }

    @Test
    fun `a deferred that completes within the timeout resolves as ready`() = runBlocking {
        val ready = CompletableDeferred<Any>()
        val outcome = coroutineScope {
            launch { ready.complete(Any()) }
            awaitGraphReady(
                graphNow = null,
                ready = ready,
                serviceAlive = true,
                timeoutMs = 5_000,
            )
        }
        assertTrue(outcome is GraphReadyOutcome.Ready)
    }

    @Test
    fun `a slow bootstrap times out into bootstrapping`() = runBlocking {
        val ready = CompletableDeferred<Any>() // never completed
        val outcome = awaitGraphReady(
            graphNow = null,
            ready = ready,
            serviceAlive = true,
            timeoutMs = 50,
        )
        assertEquals(GraphReadyOutcome.Bootstrapping, outcome)
    }

    @Test
    fun `a failed bootstrap with a live service reads as bootstrapping`() = runBlocking {
        // The dangling-signal shape: completeExceptionally from a failed init
        // while the service stays alive (the watchdog keeps retrying).
        val ready = CompletableDeferred<Any>()
        ready.completeExceptionally(IllegalStateException("init failed"))
        val outcome = awaitGraphReady(
            graphNow = null,
            ready = ready,
            serviceAlive = true,
            timeoutMs = 5_000,
        )
        assertEquals(GraphReadyOutcome.Bootstrapping, outcome)
    }

    @Test
    fun `a failed bootstrap with a dead service reads as stopped`() = runBlocking {
        val ready = CompletableDeferred<Any>()
        ready.completeExceptionally(IllegalStateException("service destroyed mid-bootstrap"))
        val outcome = awaitGraphReady(
            graphNow = null,
            ready = ready,
            serviceAlive = false,
            timeoutMs = 5_000,
        )
        assertEquals(GraphReadyOutcome.Stopped, outcome)
    }

    @Test
    fun `caller cancellation is never swallowed`() {
        val cancelled = AtomicBoolean(false)
        val ready = CompletableDeferred<Any>() // never completed
        runBlocking {
            val job = launch {
                try {
                    awaitGraphReady(
                        graphNow = null,
                        ready = ready,
                        serviceAlive = true,
                        timeoutMs = 60_000,
                    )
                } catch (e: CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }
            // Let the awaiter suspend, then cancel it.
            kotlinx.coroutines.delay(50)
            job.cancel()
            job.join()
        }
        assertTrue("caller cancellation must propagate, not become an outcome", cancelled.get())
    }
}
