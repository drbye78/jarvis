package com.jarvis.assistant.integration

import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.SberStreamingAsr
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
 * P2.2 — LIVE SaluteSpeech ASR smoke test (REMEDIATION_PLAN Phase 2).
 * LOCAL ONLY: runs via `./gradlew :app:integrationTest` when
 * `local.secrets.properties` (or JARVIS_SALUTE_* env vars) provides the
 * Salute OAuth credentials; skips through a JUnit assumption everywhere else
 * (CI has no credentials and can never fail because of this class).
 *
 * HONEST LIMITATION (task spec): this environment has no real speech audio,
 * so the test sends 1 s of SYNTHETIC SILENCE and asserts PROTOCOL HEALTH, not
 * transcription: the stream must open, authenticate (no UNAUTHENTICATED /
 * PERMISSION_DENIED), produce a terminal resolution within the budget, and
 * close cleanly. An EMPTY or near-empty transcript is the EXPECTED outcome
 * for silence and is explicitly accepted here — transcription quality is not
 * asserted by the live tier.
 *
 * Quota awareness: ~1 s of audio, `no_speech_timeout` = 3 s so the server
 * ends the utterance quickly, one round trip per run.
 */
class SaluteAsrLiveSmokeTest {

    private companion object {
        const val ASR_NO_SPEECH_TIMEOUT_SEC = 3L

        /** 1 s of 16 kHz s16le mono silence, sent in 100 ms frames. */
        const val ASR_SILENCE_FRAMES = 10
        const val ASR_FRAME_BYTES = 3_200
        const val ROUND_TRIP_TIMEOUT_MS = 45_000L
    }

    private lateinit var channel: ManagedChannel
    private lateinit var tokenManager: TokenManager

    @Before
    fun requireSaluteCredentials() {
        LiveSecrets.assumeSalute("Salute ASR live smoke test")
        channel = saluteChannel()
        tokenManager = liveTokenManager(LiveSecrets.secrets)
    }

    @After
    fun tearDown() {
        if (::channel.isInitialized) channel.shutdownNow()
    }

    @Test
    fun `synthetic silence round trip opens authenticates and closes cleanly`() = runBlocking {
        val asr = SberStreamingAsr(
            tokenManager,
            channel,
            deadlineMs = 30_000,
            noSpeechTimeoutSec = ASR_NO_SPEECH_TIMEOUT_SEC,
        )
        val stream = asr.open()
        try {
            // 1 s of synthetic silence — NEVER user audio, and the bytes are
            // not stored anywhere.
            repeat(ASR_SILENCE_FRAMES) {
                stream.send(ByteArray(ASR_FRAME_BYTES))
                delay(100)
            }
            stream.finish()

            val terminal = withTimeoutOrNull(ROUND_TRIP_TIMEOUT_MS) {
                awaitEvent(stream.events, timeoutMs = ROUND_TRIP_TIMEOUT_MS - 5_000) {
                    it is AsrEvent.Final || it is AsrEvent.Failed
                }
            } ?: fail("live ASR stream produced no terminal event within $ROUND_TRIP_TIMEOUT_MS ms")

            when (terminal) {
                is AsrEvent.Final -> {
                    // Silence input: an empty transcript is EXPECTED (see class
                    // KDoc). Only a sanity cap is asserted — never content.
                    assertTrue(
                        "transcript for silence should be empty/near-empty, was ${terminal.text.length} chars",
                        terminal.text.length <= 200,
                    )
                }
                is AsrEvent.Failed -> assertProtocolFailureIsHealthy(terminal.cause)
            }

            // Post-terminal lifecycle calls must be safe no-ops (pipeline
            // hygiene under real-network conditions).
            stream.send(ByteArray(ASR_FRAME_BYTES))
            stream.finish()
            stream.cancel()
        } finally {
            stream.cancel()
        }
    }

    private fun assertProtocolFailureIsHealthy(cause: Throwable) {
        val status = (cause as? StatusRuntimeException)?.status?.code
        when {
            status == Status.Code.UNAUTHENTICATED || status == Status.Code.PERMISSION_DENIED ->
                fail("live service rejected the credentials ($status) — check jarvis.salute.* values")

            status != null ->
                fail("live ASR stream failed with gRPC status $status (service/transport problem)")

            else ->
                // No gRPC status → the client's own clean-close marker ("ASR
                // stream completed without end-of-utterance"): a well-formed
                // server half-close. Acceptable protocol health.
                assertTrue(
                    "unexpected non-gRPC failure: ${cause.javaClass.name}",
                    cause.message?.contains("completed without end-of-utterance") == true,
                )
        }
    }
}
