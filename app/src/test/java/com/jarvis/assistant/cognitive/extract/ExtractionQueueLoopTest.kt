package com.jarvis.assistant.cognitive.extract

import com.jarvis.assistant.llm.LlmClient
import com.jarvis.assistant.model.ChatRequest
import com.jarvis.assistant.model.LlmChunk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The §6.2 drain loop's start() idempotency: BOTH owned jobs must be guarded
 * on their own [kotlinx.coroutines.Job]. The audit found the settings-watch
 * launched unconditionally, so a restart after the drain job died leaked a
 * second collector onto the settings flow.
 */
class ExtractionQueueLoopTest {

    /** Counts how many collectors actually begin (registration), not wall-clock. */
    private class CountingSettingsFlow : Flow<Unit> {
        val collectors = AtomicInteger()

        override suspend fun collect(collector: FlowCollector<Unit>) {
            collectors.incrementAndGet()
            awaitCancellation()
        }
    }

    private fun stubWorker(queueDao: FakeExtractionQueueDao): ExtractionQueueWorker =
        ExtractionQueueWorker(
            queueDao = queueDao,
            factDao = FakeUserFactDao(),
            metaDao = FakeMemoryMetaDao(),
            messageDao = FakeMessageDao(),
            llm = object : LlmClient {
                override fun chatStream(request: ChatRequest): Flow<LlmChunk> =
                    flow { emit(LlmChunk.Done) }
            },
        )

    private fun loop(
        scope: CoroutineScope,
        settings: CountingSettingsFlow,
    ): ExtractionQueueLoop {
        val queueDao = FakeExtractionQueueDao()
        return ExtractionQueueLoop(
            scope = scope,
            queueDao = queueDao,
            worker = stubWorker(queueDao),
            memoryEnabled = MutableStateFlow(false),
            autoExtractEnabled = MutableStateFlow(false),
            cloudEnabled = MutableStateFlow(false),
            nowMs = { 0L },
            settingsChanged = settings,
        )
    }

    // Dispatchers.Unconfined runs each launch body synchronously up to its
    // first suspension, so these assertions need no wall-clock wait.
    @Test
    fun `start twice registers exactly one settings watcher`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = CountingSettingsFlow()
            val loop = loop(scope, settings)

            loop.start()
            loop.start()

            assertEquals(
                "settings watcher must be registered exactly once",
                1,
                settings.collectors.get(),
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `repeated start never stacks settings collectors`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = CountingSettingsFlow()
            val loop = loop(scope, settings)

            repeat(5) { loop.start() }

            assertEquals(1, settings.collectors.get())
        } finally {
            scope.cancel()
        }
    }
}
