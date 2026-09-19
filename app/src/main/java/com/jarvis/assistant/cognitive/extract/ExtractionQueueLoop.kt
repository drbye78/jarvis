package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.cognitive.data.ExtractionQueueDao
import com.jarvis.assistant.cognitive.data.ExtractionQueueEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * The §6.2 extraction drain loop, extracted from `CognitiveCoordinator` as
 * one of exactly two approved seams (REMEDIATION_PLAN Phase 4:
 * `extract/ExtractionQueueLoop` — the coordinator is NOT decomposed further).
 *
 * Owns the drain job, the coalescing wake channel and the batching windows
 * (≤[ExtractionQueueWorker.BATCH_SIZE] per pass, flush after
 * [IDLE_FLUSH_MS] idle, [ExtractionQueueWorker.CLOUD_BACKOFF_MS] after a
 * transport failure). Pure code motion: the crash-recovery sweep, the
 * cancellation discipline and the pacing are unchanged.
 *
 * [settingsChanged] replaces the inline `combine(...)` of the reactive
 * settings flows: the loop only needs "something changed, wake up", so it
 * stays free of the settings vocabulary the coordinator owns.
 */
class ExtractionQueueLoop(
    private val scope: CoroutineScope,
    private val queueDao: ExtractionQueueDao,
    private val worker: ExtractionQueueWorker,
    private val memoryEnabled: StateFlow<Boolean>,
    private val autoExtractEnabled: StateFlow<Boolean>,
    private val cloudEnabled: StateFlow<Boolean>,
    private val nowMs: () -> Long,
    private val settingsChanged: Flow<Unit>,
) {

    /** Wake signal for the drain loop (coalescing, never blocks the caller). */
    private val wakeChannel = Channel<Unit>(
        capacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var drainJob: Job? = null

    /**
     * Starts the drain loop (idempotent). Called by the graph on start.
     */
    // The 3 `throw e` statements are the mandatory CancellationException
    // rethrows (AGENTS: cognitive coroutines catch only IO/serialization and
    // ALWAYS rethrow CE) in two nested launch lambdas — detekt counts nested
    // throws against this outer function. Merging the jobs to satisfy the
    // count would couple the settings-watch to drain error handling.
    @Suppress("ThrowsCount")
    fun start() {
        if (drainJob?.isActive == true) return
        // Settings flips wake the loop so toggles apply live (plan principle
        // 5). The first combine emission is immediate — one harmless wake.
        scope.launch(CoroutineName("cognitive-settings-watch")) {
            settingsChanged.collect { wake() }
        }
        drainJob = scope.launch(CoroutineName("cognitive-drain")) {
            // Crash recovery: RUNNING rows from a dead process → PENDING
            // (plan §5 idempotency: work is exactly-once per message).
            try {
                queueDao.running().forEach {
                    queueDao.updateState(
                        it.messageId,
                        ExtractionQueueEntity.STATE_PENDING,
                        it.attempt,
                        null,
                        nowMs(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Cognitive: running-row recovery failed")
            }

            while (isActive) {
                val cloudOn = memoryEnabled.value && autoExtractEnabled.value && cloudEnabled.value
                if (!cloudOn) {
                    withTimeoutOrNull(IDLE_WAIT_MS) { wakeChannel.receive() }
                } else {
                    val pending = try {
                        queueDao.pendingCount()
                    } catch (e: CancellationException) {
                        throw e // P1-C (A8): stop the loop, don't fake "idle"
                    } catch (e: Exception) {
                        Timber.w(e, "Cognitive: pendingCount failed")
                        0
                    }
                    when {
                        pending == 0 ->
                            withTimeoutOrNull(IDLE_WAIT_MS) { wakeChannel.receive() }

                        else -> {
                            // Flush after the idle window even with <
                            // BATCH_SIZE (plan §6.2: "or flushes after 90 s
                            // idle"); an ingest/settings wake returns early.
                            if (pending < ExtractionQueueWorker.BATCH_SIZE) {
                                withTimeoutOrNull(IDLE_FLUSH_MS) { wakeChannel.receive() }
                            }
                            try {
                                worker.drainOnce()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Timber.e(e, "Cognitive: drain step failed")
                            }
                            if (worker.lastBatchTransportFailed) {
                                delay(ExtractionQueueWorker.CLOUD_BACKOFF_MS)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Coalescing wake: a fresh ingest, a settings flip, or a backfill request. */
    fun wake() {
        wakeChannel.trySend(Unit)
    }

    companion object {
        /** §6.2: flush a partial batch after this long without another wake. */
        const val IDLE_FLUSH_MS = 90_000L

        /** §6.2: idle poll horizon when there is nothing to drain. */
        const val IDLE_WAIT_MS = 600_000L
    }
}
