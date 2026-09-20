package com.jarvis.assistant.speech

import com.jarvis.assistant.grpc.yandextts.RawAudio
import com.jarvis.assistant.speech.grpc.FakeYandexTtsService
import com.jarvis.assistant.speech.grpc.InProcessGrpc
import com.jarvis.assistant.speech.tts.YandexSpeechTts
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 2 — direct suite for [YandexSpeechTts] over the in-process gRPC
 * transport ([InProcessGrpc]): request shape, voice/role hints, chunk
 * assembly, mid-stream failure and barge-in cancellation. No real network, no
 * credentials; all waits are timeout-bounded.
 *
 * The load-bearing assertion here is the OUTPUT FORMAT one. Yandex defaults to
 * 22050 Hz LINEAR16 **with a WAV header**, while the whole playback chain
 * assumes 24 kHz headerless PCM (AudioTrack is fed directly and the AEC
 * far-end tap resamples 24k→16k). A regression that dropped the explicit
 * `output_audio_spec` would ship audio that plays ~9 % slow and poisons the
 * echo canceller's reference signal — and would be invisible to every other
 * test in the suite, because the bytes still arrive. So it is asserted on the
 * request the fake server actually received.
 */
class YandexSpeechTtsTest {

    private lateinit var harness: InProcessGrpc
    private lateinit var fakeTts: FakeYandexTtsService

    @Before
    fun setUp() {
        fakeTts = FakeYandexTtsService()
        harness = InProcessGrpc(fakeTts)
    }

    @After
    fun tearDown() {
        harness.shutdown()
    }

    private fun newTts(deadlineMs: Long = 20_000) = YandexSpeechTts(
        apiKeyProvider = { TEST_API_KEY },
        channel = harness.channel,
        deadlineMs = deadlineMs,
    )

    /** Bounded pause so scripted chunk-delivery children run before completion. */
    private suspend fun deliveryPause() = delay(100)

    private fun List<ByteArray>.asByteLists(): List<List<Byte>> = map { it.toList() }

    @Test
    fun `synthesis requests 24 kHz headerless LINEAR16 PCM`(): Unit = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("привет", voice = "marina")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        val request = fakeTts.awaitRequest()

        val raw = request.outputAudioSpec.rawAudio
        assertEquals(
            "interrupting the agreed format plays the answer at the wrong pitch",
            RawAudio.AudioEncoding.LINEAR16_PCM,
            raw.audioEncoding,
        )
        assertEquals(
            "the playback chain and the AEC tap both assume 24 kHz",
            24_000L,
            raw.sampleRateHertz,
        )
        // A container would prepend a WAV header the player would render as a click.
        assertEquals(
            "no container may be requested",
            com.jarvis.assistant.grpc.yandextts.AudioFormatOptions.AudioFormatCase.RAW_AUDIO,
            request.outputAudioSpec.audioFormatCase,
        )

        fakeTts.completeStream()
        chunksJob.await()
    }

    @Test
    fun `text and api-key header reach the server verbatim`(): Unit = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("привет, мир", voice = "marina")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        val request = fakeTts.awaitRequest()

        assertEquals("привет, мир", request.text)
        // Yandex's scheme is `Api-Key`, not Bearer; the interceptor must not
        // have borrowed Salute's format.
        assertEquals("Api-Key $TEST_API_KEY", harness.bearerCapture.lastBearer)

        fakeTts.completeStream()
        chunksJob.await()
    }

    @Test
    fun `audio chunks arrive in order and the stream completes`() = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "marina")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf(1, 2, 3))
        fakeTts.emitChunk(byteArrayOf(4, 5))
        deliveryPause()
        fakeTts.completeStream()

        assertEquals(
            listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5)),
            chunksJob.await().asByteLists(),
        )
    }

    @Test
    fun `empty audio chunks are skipped, real chunks kept`() = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("текст", voice = "marina")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf())
        fakeTts.emitChunk(byteArrayOf(9))
        deliveryPause()
        fakeTts.completeStream()

        assertEquals(listOf(listOf<Byte>(9)), chunksJob.await().asByteLists())
    }

    @Test
    fun `a bare voice yields one voice hint and no role`(): Unit = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "marina")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        val request = fakeTts.awaitRequest()

        assertEquals(1, request.hintsCount)
        assertEquals("marina", request.getHints(0).voice)

        fakeTts.completeStream()
        chunksJob.await()
    }

    @Test
    fun `voice colon role yields both hints`(): Unit = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "marina:good")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        val request = fakeTts.awaitRequest()

        assertEquals(2, request.hintsCount)
        assertEquals("marina", request.getHints(0).voice)
        assertEquals("good", request.getHints(1).role)

        fakeTts.completeStream()
        chunksJob.await()
    }

    @Test
    fun `a trailing colon is treated as no role`(): Unit = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "marina:")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        val request = fakeTts.awaitRequest()

        assertEquals(1, request.hintsCount)
        assertEquals("marina", request.getHints(0).voice)

        fakeTts.completeStream()
        chunksJob.await()
    }

    @Test
    fun `mid-stream failure surfaces a typed error after earlier chunks`() = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "marina")

        val resultJob = async {
            val received = mutableListOf<ByteArray>()
            val error = runCatching {
                withTimeout(15_000) { flow.collect { received.add(it) } }
            }.exceptionOrNull()
            received to error
        }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf(1))
        deliveryPause()
        fakeTts.emitError(Status.INTERNAL)

        val (received, error) = resultJob.await()
        assertNotNull("flow must fail with the server error", error)
        assertTrue("typed gRPC failure expected, was $error", error is StatusRuntimeException)
        assertEquals(Status.Code.INTERNAL, (error as StatusRuntimeException).status.code)
        assertEquals(
            "chunks received before the failure must be delivered",
            listOf(listOf<Byte>(1)),
            received.asByteLists(),
        )
    }

    @Test
    fun `server CANCELLED closes the flow normally (expected barge-in)`() = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "marina")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf(1))
        deliveryPause()
        fakeTts.emitError(Status.CANCELLED)

        assertEquals(
            "barge-in close must surface collected audio, not an error",
            listOf(listOf<Byte>(1)),
            chunksJob.await().asByteLists(),
        )
    }

    @Test
    fun `deadline exceeded surfaces typed failure, no hang`() = runBlocking {
        val tts = newTts(deadlineMs = 300)
        val flow = tts.synthesizeStream("медленный сервер", voice = "marina")

        // Subscribe FIRST: the flow is cold, so without a collector no RPC is
        // ever issued and awaiting the request would hang the test.
        val errorJob = async {
            runCatching { withTimeout(15_000) { flow.toList() } }.exceptionOrNull()
        }
        fakeTts.awaitRequest() // request reached the fake; server never responds
        val error = errorJob.await()

        assertNotNull("hung synthesis must fail, not hang the session", error)
        assertTrue("typed gRPC failure expected, was $error", error is StatusRuntimeException)
        assertEquals(Status.Code.DEADLINE_EXCEEDED, (error as StatusRuntimeException).status.code)
    }

    @Test
    fun `downstream cancel cancels the synthesis RPC server-side`() = runBlocking {
        val tts = newTts()
        val flow = tts.synthesizeStream("длинное предложение", voice = "marina")

        val chunksJob = launch { flow.take(1).toList() }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf(1))
        chunksJob.join()

        assertTrue("barge-in must abort the in-flight RPC", fakeTts.awaitClientCancelled())
    }

    private companion object {
        const val TEST_API_KEY = "test-api-key"
    }
}
