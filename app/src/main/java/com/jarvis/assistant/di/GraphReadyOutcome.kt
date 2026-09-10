package com.jarvis.assistant.di

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Outcome of [awaitGraphReady]: the bootstrap phase (~1 min on Kirin-class
 * devices, the graph is rebuilt on every service start) must not read as
 * "not running" to the UI — Settings buttons and the home toggle used to
 * toast «Запустите ассистента» and offer «Запустить» while the service was
 * mid-bootstrap, and a failed bootstrap's dangling signal was invisible.
 */
sealed interface GraphReadyOutcome<out T> {
    /** A live, fully constructed graph (was already up, or finished in time). */
    data class Ready<T>(val graph: T) : GraphReadyOutcome<T>

    /**
     * A service instance is alive but the graph is still building — or the
     * last build failed and the watchdog keeps retrying. "Try shortly",
     * never "not running".
     */
    data object Bootstrapping : GraphReadyOutcome<Nothing>

    /** No live service: nothing to await. */
    data object Stopped : GraphReadyOutcome<Nothing>
}

/**
 * Bounded wait for the assistant graph, pure and JVM-testable:
 *
 * - [graphNow] non-null → [GraphReadyOutcome.Ready] immediately (no suspend).
 * - [ready] null (no service attached) → [GraphReadyOutcome.Stopped].
 * - [ready] completes within [timeoutMs] → [GraphReadyOutcome.Ready].
 * - [ready] does not complete in time → [GraphReadyOutcome.Bootstrapping].
 * - [ready] completes exceptionally (init failed / service died
 *   mid-bootstrap) → [GraphReadyOutcome.Bootstrapping] while [serviceAlive]
 *   (the watchdog keeps retrying), otherwise [GraphReadyOutcome.Stopped].
 *
 * Callers pass `GraphHolder.graph`, `GraphHolder.service?.graphReady` and
 * `GraphHolder.service != null` freshly at every call so a reset deferred
 * (a fresh bootstrap attempt after a failure) is always observed.
 *
 * Cancellation of the CALLER is never swallowed: only the timeout's own
 * [TimeoutCancellationException] is mapped onto an outcome.
 */
suspend fun <T> awaitGraphReady(
    graphNow: T?,
    ready: CompletableDeferred<T>?,
    serviceAlive: Boolean,
    timeoutMs: Long,
): GraphReadyOutcome<T> {
    if (graphNow != null) return GraphReadyOutcome.Ready(graphNow)
    if (ready == null) return GraphReadyOutcome.Stopped
    return try {
        GraphReadyOutcome.Ready(withTimeout(timeoutMs) { ready.await() })
    } catch (e: TimeoutCancellationException) {
        GraphReadyOutcome.Bootstrapping
    } catch (e: CancellationException) {
        throw e // caller cancelled — propagate, never report as an outcome
    } catch (_: Throwable) {
        if (serviceAlive) GraphReadyOutcome.Bootstrapping else GraphReadyOutcome.Stopped
    }
}
