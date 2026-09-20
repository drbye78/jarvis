package com.jarvis.assistant.speech

import com.jarvis.assistant.grpc.yandexstt.RawAudio
import com.jarvis.assistant.grpc.yandexstt.RecognitionModelOptions
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.YandexStreamingAsr
import com.jarvis.assistant.speech.grpc.FakeYandexAsrService
import com.jarvis.assistant.speech.grpc.InProcessGrpc
import com.jarvis.assistant.speech.grpc.collectEvents
import io.grpc.Status
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 — direct suite for [YandexStreamingAsr] over the in-process gRPC
 * transport ([InProcessGrpc]): session-options shape and ordering, audio
 * framing, event mapping, error handling and teardown. No real network, no
 * credentials; all waits are timeout-bounded so a broken client fails the test
 * instead of hanging the suite.
 *
 * Two behaviors are load-bearing and pinned below:
 * - `session_options` MUST be the FIRST message on the stream (the proto says
 *   so explicitly); sending audio first is rejected by the service.
 * - `final` is Yandex's EOU boundary, and the service emits a SECOND, empty
 *   `final` after the utterance closes (observed against the live service).
 *   The client must surface exactly ONE [AsrEvent.Final] per turn, so the
 *   terminal gate is asserted rather than assumed.
 */
class YandexStreamingAsrTest {

    private lateinit var harness: InProcessGrpc
    private lateinit var fakeAsr: FakeYandexAsrService

    @Before
    fun setUp() {
        fakeAsr = FakeYandexAsrService()
        harness = InProcessGrpc(fakeAsr)
    }

    @After
    fun tearDown() {
        harness.shutdown()
    }

    private fun newAsr(deadlineMs: Long = 90_000) = YandexStreamingAsr(
        apiKeyProvider = { TEST_API_KEY },
        channel = harness.channel,
        deadlineMs = deadlineMs,
    )

    @Test
    fun `session options are sent first with 16 kHz raw PCM`() = runBlocking {
        val stream = newAsr().open()
        try {
            val requests = fakeAsr.receivedRequests()
            assertTrue("at least session_options must be on the wire", requests.isNotEmpty())

            val first = requests.first()
            assertTrue(
                "session_options MUST precede any audio chunk",
                first.hasSessionOptions(),
            )

            val model = first.sessionOptions.recognitionModel
            assertEquals("general", model.model)
            assertEquals(
                RawAudio.AudioEncoding.LINEAR16_PCM,
                model.audioFormat.rawAudio.audioEncoding,
            )
            assertEquals(
                "input rate must match AudioSpec.MIC",
                16_000L,
                model.audioFormat.rawAudio.sampleRateHertz,
            )
            assertEquals(1L, model.audioFormat.rawAudio.audioChannelCount)
            assertEquals(
                RecognitionModelOptions.AudioProcessingType.REAL_TIME,
                model.audioProcessingType,
            )
            assertEquals(
                listOf("ru-RU"),
                model.languageRestriction.languageCodeList,
            )
            assertEquals(
                com.jarvis.assistant.grpc.yandexstt.LanguageRestrictionOptions.LanguageRestrictionType.WHITELIST,
                model.languageRestriction.restrictionType,
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `audio frames are sent as chunks after the options`() = runBlocking {
        val stream = newAsr().open()
        try {
            stream.send(byteArrayOf(1, 2, 3))

            val requests = fakeAsr.receivedRequests()
            assertEquals(2, requests.size)
            assertTrue("options first", requests[0].hasSessionOptions())
            assertTrue("then audio", requests[1].hasChunk())
            assertEquals(
                listOf<Byte>(1, 2, 3),
                requests[1].chunk.data.toByteArray().toList(),
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `partials surface and a final ends the utterance`() = runBlocking {
        val stream = newAsr().open()
        try {
            // Subscribe BEFORE the server emits: the flow keeps only the last
            // event in its replay cache, so pre-subscription emissions would
            // lose the partials.
            val eventsJob = async { collectEvents(stream.events, count = 3) }
            yield() // collector is now subscribed (single-threaded runBlocking loop)

            fakeAsr.emitPartial("приве")
            fakeAsr.emitPartial("привет ")
            fakeAsr.emitFinal("привет как дела")

            assertEquals(
                listOf(
                    AsrEvent.Partial("приве"),
                    AsrEvent.Partial("привет"),
                    AsrEvent.Final("привет как дела"),
                ),
                eventsJob.await(),
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `a second empty final does not produce a second terminal event`() = runBlocking {
        val stream = newAsr().open()
        try {
            // ONE subscription records everything. A fresh `events.first()`
            // would instead re-read the replay=1 cache and see the first Final
            // again — that is a harness artifact, not a second terminal event.
            val received = mutableListOf<AsrEvent>()
            val collector = launch { stream.events.collect { received.add(it) } }
            yield()

            fakeAsr.emitFinal("привет")
            withTimeout(2_000) {
                while (received.none { it is AsrEvent.Final }) delay(10)
            }
            assertEquals(listOf(AsrEvent.Final("привет")), received.toList())

            // The live service follows the utterance final with an empty one.
            // The terminal gate must swallow it: a second Final would re-arm
            // the turn and speak the answer twice.
            fakeAsr.emitFinal("")
            delay(300)
            assertEquals(
                "exactly one Final may reach the session, regardless of trailing finals",
                listOf(AsrEvent.Final("привет")),
                received.filterIsInstance<AsrEvent.Final>(),
            )
            collector.cancel()
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `a blank final is surfaced as an empty Final so the session can map NoSpeech`() = runBlocking {
        val stream = newAsr().open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()
            fakeAsr.emitFinal("")
            assertEquals(
                "an EOU with no transcript must reach the session as Final(\"\")",
                AsrEvent.Final(""),
                eventsJob.await().single(),
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `a keep-alive status code is not surfaced as a transcript`() = runBlocking {
        val stream = newAsr().open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()

            fakeAsr.emitWarning()
            fakeAsr.emitFinal("после статуса")

            assertEquals(
                "the WARNING status must be invisible to the session",
                listOf(AsrEvent.Final("после статуса")),
                eventsJob.await(),
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `a server error surfaces as Failed with the typed status`() = runBlocking {
        val stream = newAsr().open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()
            fakeAsr.emitError(Status.UNAUTHENTICATED)

            val event = eventsJob.await().single()
            assertTrue("expected a Failed event, got $event", event is AsrEvent.Failed)
            val cause = (event as AsrEvent.Failed).cause
            val code = (cause as? io.grpc.StatusRuntimeException)?.status?.code
            assertEquals(Status.Code.UNAUTHENTICATED, code)
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `finish half-closes the stream`() = runBlocking {
        val stream = newAsr().open()
        try {
            stream.finish()
            assertTrue(
                "finish() must half-close the client side of the bidi call",
                fakeAsr.awaitClientCompleted(),
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `cancel aborts the RPC`() = runBlocking {
        val stream = newAsr().open()
        stream.cancel()
        assertNotNull(
            "cancel() must abort the in-flight call",
            fakeAsr.awaitClientError(),
        )
    }

    @Test
    fun `send after cancel is dropped without throwing`() = runBlocking {
        val stream = newAsr().open()
        stream.cancel()

        val before = fakeAsr.receivedRequests().size
        stream.send(byteArrayOf(9, 9, 9))
        assertEquals(
            "frames after teardown must not reach the dead RPC",
            before,
            fakeAsr.receivedRequests().size,
        )
    }

    @Test
    fun `server completion without EOU surfaces a failure`() = runBlocking {
        val stream = newAsr().open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()
            fakeAsr.completeStream()

            val event = eventsJob.await().single()
            assertTrue(
                "completion without an EOU leaves the turn unresolved — must fail honestly",
                event is AsrEvent.Failed,
            )
        } finally {
            stream.cancel()
        }
    }

    private companion object {
        const val TEST_API_KEY = "test-api-key"
    }
}
