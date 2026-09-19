package com.jarvis.assistant.speech

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.grpc.recognition.BackendInfo
import com.jarvis.assistant.grpc.recognition.RecognitionResponse
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.SberStreamingAsr
import com.jarvis.assistant.speech.grpc.FakeSaluteAsrService
import com.jarvis.assistant.speech.grpc.InProcessGrpc
import com.jarvis.assistant.speech.grpc.awaitEvent
import com.jarvis.assistant.speech.grpc.collectEvents
import com.jarvis.assistant.util.InMemoryVault
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1.2 — direct suite for [SberStreamingAsr] (previously zero tests) over the
 * in-process gRPC transport ([InProcessGrpc]): no real network, no credentials.
 * The fake server scripts results/errors/deadlines; all waits are timeout-bounded
 * so a broken client fails the test instead of hanging the suite.
 *
 * ACTUAL-BEHAVIOR note (documented, not bent): the client fetches its token ONCE
 * per [SberStreamingAsr.open] and has NO mid-stream refresh/retry on 401 — a
 * mid-stream UNAUTHENTICATED error is a terminal `AsrEvent.Failed`; the refreshed
 * token is picked up by the NEXT open() (whose TokenManager call refreshes only
 * when the cached token is expired/invalidated). These tests pin that actual
 * behavior; a mid-stream refresh policy, if ever added, will update them.
 *
 * Production-code note: `SberStreamingAsr` extracts the error status via
 * `(t as? StatusException)?.status` and falls back to
 * `(t as? StatusRuntimeException)?.status` — the async stub delivers the
 * latter, and the fallback keeps the log label a typed gRPC status code
 * instead of degrading to the raw message. The typed [AsrEvent.Failed] event
 * itself is emitted correctly in every scenario (asserted against the real
 * exception type below).
 */
class SberStreamingAsrTest {

    private lateinit var oauthServer: MockWebServer
    private lateinit var harness: InProcessGrpc
    private lateinit var fakeAsr: FakeSaluteAsrService
    private lateinit var tokenManager: TokenManager

    @Before
    fun setUp() {
        oauthServer = MockWebServer()
        oauthServer.start()
        fakeAsr = FakeSaluteAsrService()
        harness = InProcessGrpc(fakeAsr)
        tokenManager = TokenManager(
            null,
            OkHttpClient(),
            JarvisConfig(oauthEndpoint = oauthServer.url("/oauth").toString()),
            InMemoryVault(),
        ) { _ -> "test-client" to "test-secret" }
    }

    @After
    fun tearDown() {
        harness.shutdown()
        oauthServer.shutdown()
    }

    private fun enqueueToken(token: String) {
        oauthServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"$token","expires_in":3600}"""),
        )
    }

    private fun newAsr(deadlineMs: Long = 60_000) = SberStreamingAsr(
        tokenManager,
        harness.channel,
        deadlineMs = deadlineMs,
        noSpeechTimeoutSec = 7,
    )

    @Test
    fun `partial results surface and final EOU ends the utterance`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            // Subscribe BEFORE the server emits: the flow keeps only the last
            // event in its replay cache, so emissions before a subscription
            // would lose the partials.
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

            // The wire contract: the first client frame carries the options.
            val requests = fakeAsr.receivedRequests()
            val options = requests.firstOrNull { it.hasOptions() }
            assertNotNull("client must open with the options frame", options)
            assertEquals(16_000, options!!.options.sampleRate)
            assertEquals("ru-RU", options.options.language)
            assertEquals("general", options.options.model)
            assertTrue(options.options.enablePartialResults.enable)
            assertEquals(7L, options.options.noSpeechTimeout.seconds)

            // Sent PCM must reach the server verbatim.
            val pcm = ByteArray(64) { it.toByte() }
            stream.send(pcm)
            withTimeoutOrNull(5_000) {
                while (fakeAsr.receivedRequests().none { it.hasAudioChunk() }) delay(10)
            }
            assertTrue(
                "sent PCM must reach the server verbatim",
                fakeAsr.receivedRequests().last { it.hasAudioChunk() }.audioChunk.toByteArray()
                    .contentEquals(pcm),
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `no-speech timeout EOU with empty transcript maps to Final blank`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 2) }
            yield()

            fakeAsr.emitPartial("про") // interim speech, then silence
            fakeAsr.emitNoSpeechTimeout()

            val events = eventsJob.await()
            assertEquals(AsrEvent.Partial("про"), events[0])
            assertEquals(
                "NO_SPEECH_TIMEOUT EOU must be Final(\"\") so the session maps it to NoSpeech",
                AsrEvent.Final(""),
                events[1],
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `deadline exceeded surfaces as an honest typed failure, no hang`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr(deadlineMs = 300)
        val stream = asr.open()
        try {
            val start = System.nanoTime()
            val failed = awaitEvent(stream.events, timeoutMs = 5_000) { it is AsrEvent.Failed } as AsrEvent.Failed
            val elapsedMs = (System.nanoTime() - start) / 1_000_000

            assertTrue("deadline must resolve well under the 5s test budget", elapsedMs < 5_000)
            val cause = (failed as AsrEvent.Failed).cause
            assertTrue("cause must be a gRPC Status exception, was $cause", cause is StatusRuntimeException)
            assertEquals(Status.Code.DEADLINE_EXCEEDED, (cause as StatusRuntimeException).status.code)
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `mid-stream unauthenticated is terminal failure with NO mid-stream refresh`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 2) }
            yield()

            fakeAsr.emitPartial("начал")
            fakeAsr.emitError(Status.UNAUTHENTICATED) // the "401 mid-utterance"

            val events = eventsJob.await()
            assertEquals(AsrEvent.Partial("начал"), events[0])
            val cause = (events[1] as AsrEvent.Failed).cause
            assertTrue("cause must be a gRPC Status exception", cause is StatusRuntimeException)
            assertEquals(Status.Code.UNAUTHENTICATED, (cause as StatusRuntimeException).status.code)
            assertEquals("Bearer tok-1", harness.bearerCapture.lastBearer)

            // ACTUAL behavior: no mid-stream token refresh attempt — the failure
            // is terminal; refresh happens on the next open() only.
            assertEquals(1, oauthServer.requestCount)

            // Next turn with a still-fresh cached token: no new OAuth request.
            val stream2 = asr.open()
            try {
                assertEquals(1, oauthServer.requestCount)
            } finally {
                stream2.cancel()
            }
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `invalidated token is refreshed on the NEXT open with the new bearer`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream1 = asr.open()
        stream1.cancel()
        assertEquals("Bearer tok-1", harness.bearerCapture.lastBearer)

        tokenManager.invalidate()
        enqueueToken("tok-2")
        val stream2 = asr.open()
        stream2.cancel()

        assertEquals(2, oauthServer.requestCount)
        assertEquals("Bearer tok-2", harness.bearerCapture.lastBearer)
    }

    @Test
    fun `stream dies mid-utterance surfaces failure and pipeline survives`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 2) }
            yield()

            fakeAsr.emitPartial("сервер упал по")
            fakeAsr.emitError(Status.UNAVAILABLE)

            val events = eventsJob.await()
            val cause = (events[1] as AsrEvent.Failed).cause
            assertEquals(Status.Code.UNAVAILABLE, (cause as StatusRuntimeException).status.code)

            // Post-failure lifecycle calls must be safe no-ops (no crash, no leak).
            stream.send(ByteArray(32))
            stream.finish()
            stream.cancel() // idempotent by contract
            // No new event (other than the replayed Failed) within a bounded window.
            val extra = withTimeoutOrNull(300) { stream.events.first { it !is AsrEvent.Failed } }
            assertNull("no new events may follow the failure", extra)
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `malformed and garbled server events are skipped without crashing`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()

            // Not a transcription (backend heartbeat) → skipped.
            fakeAsr.emitNonTranscription(
                RecognitionResponse.newBuilder()
                    .setBackendInfo(BackendInfo.newBuilder().setModelName("gigaspeech"))
                    .build(),
            )
            // Empty oneof (garbled frame) → skipped.
            fakeAsr.emitNonTranscription(RecognitionResponse.getDefaultInstance())
            // Transcription with no hypotheses and no EOU → no partial.
            fakeAsr.emitNonTranscription(
                RecognitionResponse.newBuilder()
                    .setTranscription(
                        com.jarvis.assistant.grpc.recognition.Transcription.newBuilder().setEou(false),
                    )
                    .build(),
            )
            // The stream must still work afterwards.
            fakeAsr.emitFinal("выжил")

            assertEquals(listOf(AsrEvent.Final("выжил")), eventsJob.await())
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `client cancel aborts the call server-side`() = runBlocking {
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        stream.send(ByteArray(16))
        stream.cancel()
        stream.cancel() // idempotent
        val clientError = fakeAsr.awaitClientError()
        assertNotNull("cancel must abort the server-side stream", clientError)
    }

    @Test
    fun `send after server completion is a no-op - no frames pushed into the dead RPC`() = runBlocking {
        // Feed-after-death regression: onError/onCompleted now close the
        // feeder gate. Server half-close (no client finish()) is the
        // observable variant: the client's send direction is still OPEN at
        // the transport level, so WITHOUT the fix a send() after
        // onCompleted was delivered to the dead call's listener; with the
        // fix it must be dropped before the observer is touched.
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            fakeAsr.completeStream() // server half-closes without EOU
            awaitEvent(stream.events, timeoutMs = 5_000) { it is AsrEvent.Failed }

            stream.send(ByteArray(16))
            stream.send(ByteArray(16))
            stream.send(ByteArray(16))

            assertEquals(
                "no audio chunk may reach the server after its half-close",
                0,
                fakeAsr.receivedRequests().count { it.hasAudioChunk() },
            )
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `a late partial after the final does not replace the replayed terminal`() = runBlocking {
        // S-3 regression: the flow keeps the last event in its replay cache
        // (replay=1) for a subscriber that attaches after an instant terminal.
        // A late non-EOU frame used to overwrite that cached Final with a
        // Partial, so the late subscriber never learned the utterance ended.
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()
            fakeAsr.emitFinal("привет")
            assertEquals(AsrEvent.Final("привет"), eventsJob.await().single())

            fakeAsr.emitPartial("привет ещё")

            val late = withTimeoutOrNull(300) { stream.events.first { it is AsrEvent.Partial } }
            assertNull("a late Partial must not be emitted after the terminal", late)
        } finally {
            stream.cancel()
        }
    }

    @Test
    fun `final EOU followed by server completion does not emit a spurious Failed`() = runBlocking {
        // Terminal-gate regression: onCompleted used to emit Failed
        // unconditionally — a spurious failure event AFTER a Final had
        // already been delivered.
        enqueueToken("tok-1")
        val asr = newAsr()
        val stream = asr.open()
        try {
            val eventsJob = async { collectEvents(stream.events, count = 1) }
            yield()
            fakeAsr.emitFinal("готово")
            assertEquals(AsrEvent.Final("готово"), eventsJob.await().single())

            // The server closes its side AFTER the EOU (normal lifecycle).
            fakeAsr.completeStream()

            // No Failed may follow the Final within a bounded window.
            val extra = withTimeoutOrNull(300) {
                stream.events.first { it is AsrEvent.Failed }
            }
            assertNull("no Failed may follow the Final", extra)
        } finally {
            stream.cancel()
        }
    }
}
