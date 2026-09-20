package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.YandexStreamingAsr
import com.jarvis.assistant.speech.grpc.awaitEvent
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * LIVE Yandex SpeechKit v3 recognition smoke test. LOCAL ONLY: runs via
 * `./gradlew :app:integrationTest` when `local.secrets.properties` (or
 * `JARVIS_YANDEX_API_KEY`) provides the API key; skips through a JUnit
 * assumption everywhere else (CI has no credentials and can never fail
 * because of this class).
 *
 * WHAT THIS PROVES that the in-process tests cannot: the vendored proto
 * descriptors, the `Authorization: Api-Key` header, and the streaming
 * session-options shape are all accepted by the REAL service over TLS — i.e.
 * the contract was read correctly, not just self-consistently. The in-process
 * harness would happily agree with a wrong header name or a mis-numbered
 * field, so this is the only tier that can catch a vendoring mistake.
 *
 * HONEST LIMITATION (same as the Salute tier): no real speech audio is
 * available here, so 1 s of SYNTHETIC SILENCE is sent and PROTOCOL HEALTH is
 * asserted, not transcription. An empty transcript is the EXPECTED outcome for
 * silence and is explicitly accepted.
 *
 * QUOTA: `final` requires the server EOU classifier to fire, which silence
 * alone may not trigger before the deadline. Both a `Final` (EOU fired) and a
 * `Failed` that is a clean deadline/half-close are therefore accepted; only an
 * AUTH or PANIC outcome fails.
 */
class YandexAsrLiveSmokeTest {

    private companion object {
        /** 1 s of 16 kHz s16le mono silence, sent in 100 ms frames. */
        const val ASR_SILENCE_FRAMES = 10
        const val ASR_FRAME_BYTES = 3_200
        const val ROUND_TRIP_TIMEOUT_MS = 45_000L
    }

    private lateinit var channel: ManagedChannel

    @Before
    fun requireYandexCredentials() {
        LiveSecrets.assumeYandex("Yandex ASR live smoke test")
        channel = yandexChannel(JarvisConfig().yandexSttEndpoint)
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.shutdownNow()
    }

    @Test
    fun `synthetic silence round trip authenticates and resolves terminally`() = runBlocking {
        val asr = YandexStreamingAsr(
            apiKeyProvider = { LiveSecrets.secrets.yandexApiKey.orEmpty() },
            channel = channel,
            deadlineMs = 30_000,
        )
        val stream = asr.open()
        try {
            repeat(ASR_SILENCE_FRAMES) {
                stream.send(ByteArray(ASR_FRAME_BYTES))
                delay(100)
            }
            stream.finish()

            val terminal = withTimeoutOrNull(ROUND_TRIP_TIMEOUT_MS) {
                awaitEvent(stream.events, timeoutMs = ROUND_TRIP_TIMEOUT_MS - 5_000) {
                    it is AsrEvent.Final || it is AsrEvent.Failed
                }
            } ?: fail("live Yandex ASR stream produced no terminal event within $ROUND_TRIP_TIMEOUT_MS ms")

            when (terminal) {
                is AsrEvent.Final -> assertTrue(
                    "transcript for silence should be empty/near-empty, was ${terminal.text.length} chars",
                    terminal.text.length <= 200,
                )
                is AsrEvent.Failed -> assertFailureIsHealthy(terminal.cause)
            }

            stream.send(ByteArray(ASR_FRAME_BYTES))
            stream.finish()
            stream.cancel()
        } finally {
            stream.cancel()
        }
    }

    /**
     * A `Failed` is acceptable ONLY when it is a clean transport/server
     * resolution (deadline, half-close, cancellation). An auth rejection means
     * the key or the header scheme is wrong — that is the failure this test
     * exists to catch, so it is asserted as hard.
     */
    private fun assertFailureIsHealthy(cause: Throwable) {
        val status = (cause as? StatusRuntimeException)?.status?.code
            ?: (cause as? io.grpc.StatusException)?.status?.code
        when (status) {
            Status.Code.UNAUTHENTICATED ->
                fail("live service rejected the API key (UNAUTHENTICATED) — check jarvis.yandex.apiKey")

            Status.Code.PERMISSION_DENIED ->
                fail("API key lacks the speechkit role (PERMISSION_DENIED) — grant it in Yandex Cloud")

            Status.Code.RESOURCE_EXHAUSTED ->
                fail("Yandex speech quota exhausted (RESOURCE_EXHAUSTED)")

            Status.Code.INVALID_ARGUMENT ->
                fail(
                    "service rejected the streaming request shape (INVALID_ARGUMENT) " +
                        "— likely a proto/vendoring problem",
                )

            Status.Code.DEADLINE_EXCEEDED, Status.Code.CANCELLED ->
                // Expected for silence: no EOU fired before the deadline, or
                // the stream was cancelled during teardown. Protocol health is
                // unaffected.
                Unit

            null ->
                // No gRPC status → the client's own clean-close marker ("ASR
                // stream completed without end-of-utterance"): the server
                // half-closed after the silence. Any OTHER exception-shaped
                // value would be a real client bug, so it is asserted (never
                // blanket-accepted just because it arrived as a Failed).
                assertTrue(
                    "unexpected non-gRPC failure: ${cause.javaClass.name}: ${cause.message}",
                    cause.message?.contains("completed without end-of-utterance") == true,
                )

            else ->
                fail("live Yandex ASR stream failed with gRPC status $status (service/transport problem)")
        }
    }
}
