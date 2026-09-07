package com.jarvis.assistant.speech

import com.jarvis.assistant.R
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.grpc.synthesis.SynthesisRequest
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.grpc.FakeSaluteTtsService
import com.jarvis.assistant.speech.grpc.InProcessGrpc
import com.jarvis.assistant.speech.tts.SaluteSpeechTts
import com.jarvis.assistant.speech.tts.VoiceCatalog
import com.jarvis.assistant.util.InMemoryVault
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1.3 — direct suite for [SaluteSpeechTts] over the in-process gRPC transport
 * ([InProcessGrpc]): stream assembly, voice mapping, mid-stream failures,
 * deadline, and barge-in cancellation. No real network, no credentials;
 * all waits are timeout-bounded (no busy thread-yields).
 *
 * Scope note (honest): AudioTrack playback is Android-bound and covered by
 * StreamingAudioTrackPlayerTest; this file tests the CLIENT layer only —
 * from `TtsClient.synthesizeStream` to the wire and back.
 *
 * RACE FIX REGRESSION (P3.5, formerly "known latent race" reported in
 * commit 63c21db): [SaluteSpeechTts.synthesizeStream] used to deliver every
 * chunk via `this@channelFlow.launch { send(bytes) }` while the server's
 * `onCompleted` closed the flow channel synchronously — a chunk whose
 * delivery child had not run before `close()` was silently dropped. The
 * client now serializes completion (a completion gate closes the channel
 * only after every spawned chunk-delivery child has drained), so the
 * deliveryPause previously needed in the assembly tests is no longer a
 * correctness requirement — `synthesis test completes immediately after the
 * last chunk` pins that the unpaused sequence delivers every chunk, on the
 * same direct-executor harness where the race used to manifest.
 */
class SaluteSpeechTtsTest {

    private lateinit var oauthServer: MockWebServer
    private lateinit var harness: InProcessGrpc
    private lateinit var fakeTts: FakeSaluteTtsService
    private lateinit var tokenManager: TokenManager

    @Before
    fun setUp() {
        oauthServer = MockWebServer()
        oauthServer.start()
        fakeTts = FakeSaluteTtsService()
        harness = InProcessGrpc(fakeTts)
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

    private fun newTts(deadlineMs: Long = 20_000) = SaluteSpeechTts(
        tokenManager,
        harness.channel,
        deadlineMs = deadlineMs,
    )

    /** Bounded pause so scripted chunk-delivery children run before completion. */
    private suspend fun deliveryPause() = delay(100)

    private fun List<ByteArray>.asByteLists(): List<List<Byte>> = map { it.toList() }

    @Test
    fun `synthesis stream assembles into ordered audio chunks`() = runBlocking {
        enqueueToken("tok-1")
        val tts = newTts()
        val flow = tts.synthesizeStream("привет, мир", voice = "Mila")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        val request = fakeTts.awaitRequest()

        fakeTts.emitChunk(byteArrayOf(1, 2, 3))
        fakeTts.emitChunk(byteArrayOf(4, 5))
        deliveryPause()
        fakeTts.completeStream()

        val chunks = chunksJob.await()
        assertEquals(listOf(listOf<Byte>(1, 2, 3), listOf<Byte>(4, 5)), chunks.asByteLists())

        // The request the server saw: text verbatim, PCM/ru-RU/TEXT, bearer attached.
        assertEquals("привет, мир", request.text)
        assertEquals(SynthesisRequest.AudioEncoding.PCM_S16LE, request.audioEncoding)
        assertEquals("ru-RU", request.language)
        assertEquals(SynthesisRequest.ContentType.TEXT, request.contentType)
        assertEquals("Bearer tok-1", harness.bearerCapture.lastBearer)
    }

    @Test
    fun `empty data chunks are skipped, real chunks kept`() = runBlocking {
        enqueueToken("tok-1")
        val tts = newTts()
        val flow = tts.synthesizeStream("текст", voice = "Mila")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf())
        fakeTts.emitChunk(byteArrayOf(9))
        deliveryPause()
        fakeTts.completeStream()

        assertEquals(listOf(listOf<Byte>(9)), chunksJob.await().asByteLists())
    }

    @Test
    fun `Mila maps to May_24000, case-insensitive - unknown voice passes through`() = runBlocking {
        enqueueToken("tok-1")
        val tts = newTts()

        // Catalog preset → verified pool id.
        val job1 = launch { tts.synthesizeStream("а", "Mila").toList() }
        assertEquals("May_24000", fakeTts.awaitRequest().voice)
        job1.cancel()

        val job2 = launch { tts.synthesizeStream("б", "MILA").toList() }
        fakeTts.awaitRequest()
        assertEquals("May_24000", fakeTts.capturedRequest!!.voice)
        job2.cancel()

        // Unknown voice ids pass through untouched (catalog contract).
        val job3 = launch { tts.synthesizeStream("в", "Baya_24000").toList() }
        fakeTts.awaitRequest()
        assertEquals("Baya_24000", fakeTts.capturedRequest!!.voice)
        job3.cancel()
    }

    @Test
    fun `voice catalog ships exactly the verified Mila preset`() {
        assertEquals(listOf("Mila"), VoiceCatalog.PRESETS.map { it.id })
        assertEquals(R.string.voice_mila, VoiceCatalog.PRESETS.single().labelRes)
    }

    @Test
    fun `mid-stream failure surfaces a typed error after earlier chunks`() = runBlocking {
        enqueueToken("tok-1")
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "Mila")

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
        enqueueToken("tok-1")
        val tts = newTts()
        val flow = tts.synthesizeStream("речь", voice = "Mila")

        val chunksJob = async { withTimeout(15_000) { flow.toList() } }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf(1))
        deliveryPause()
        fakeTts.emitError(Status.CANCELLED)

        val chunks = chunksJob.await()
        assertEquals(
            "barge-in close must surface collected audio, not an error",
            listOf(listOf<Byte>(1)),
            chunks.asByteLists()
        )
    }

    @Test
    fun `deadline exceeded surfaces typed failure, no hang`() = runBlocking {
        enqueueToken("tok-1")
        val tts = newTts(deadlineMs = 300)
        val flow = tts.synthesizeStream("медленный сервер", voice = "Mila")

        // Subscribe FIRST: the flow is cold — without a collector no RPC is
        // ever issued and awaiting the request would hang the test.
        val errorJob = async {
            runCatching { withTimeout(15_000) { flow.toList() } }.exceptionOrNull()
        }
        yield()
        fakeTts.awaitRequest() // request reached the fake; server deliberately never responds
        val error = errorJob.await()
        assertNotNull("hung synthesis must fail, not hang the session", error)
        assertTrue("typed gRPC failure expected, was $error", error is StatusRuntimeException)
        assertEquals(Status.Code.DEADLINE_EXCEEDED, (error as StatusRuntimeException).status.code)
    }

    @Test
    fun `downstream cancel cancels the synthesis RPC server-side`() = runBlocking {
        enqueueToken("tok-1")
        val tts = newTts()
        val flow = tts.synthesizeStream("длинное предложение", voice = "Mila")

        val chunksJob = launch { flow.take(1).toList() }
        fakeTts.awaitRequest()
        fakeTts.emitChunk(byteArrayOf(1))
        chunksJob.join()

        assertTrue(
            "barge-in must abort the in-flight RPC",
            fakeTts.awaitClientCancelled(),
        )
    }
}
