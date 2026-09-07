package com.jarvis.assistant.speech.replay

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.integration.FIXTURE_FORMAT_VERSION
import com.jarvis.assistant.integration.RecordedResponse
import com.jarvis.assistant.integration.SaluteFixture
import com.jarvis.assistant.integration.decodeBase64
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.SberStreamingAsr
import com.jarvis.assistant.speech.grpc.FakeSaluteAsrService
import com.jarvis.assistant.speech.grpc.FakeSaluteTtsService
import com.jarvis.assistant.speech.grpc.InProcessGrpc
import com.jarvis.assistant.speech.grpc.collectEvents
import com.jarvis.assistant.speech.tts.SaluteSpeechTts
import com.jarvis.assistant.util.InMemoryVault
import io.grpc.Status
import io.grpc.StatusRuntimeException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2.3 CI replay tier: the committed, SANITIZED fixtures under
 * `app/src/test/resources/recorded/` are replayed through the Phase 1
 * in-process gRPC fakes, so the recorded real-service wire shapes keep being
 * exercised in every CI run — no credentials, no network, no secrets.
 *
 * Fixtures are (re)generated locally by `./gradlew :app:recordSaluteFixtures`
 * with the owner's credentials; the format contract lives in
 * `com.jarvis.assistant.integration.SaluteFixtures`. Every `asr_*` / `tts_*`
 * JSON file found on the test classpath is replayed dynamically, so dropping
 * a new recording into `recorded/` extends coverage without touching this
 * file. Sanitization is re-verified here: fixture `error` entries may carry
 * only a gRPC status code + exception class name.
 */
class SaluteFixtureReplayTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- fixture discovery -------------------------------------------------------------

    private fun fixtureFiles(prefix: String): List<File> {
        val classLoader = checkNotNull(SaluteFixtureReplayTest::class.java.classLoader)
        val urls = classLoader.getResources(RECORD_DIR)
        val files = urls.toList().flatMap { url ->
            val dir = File(url.toURI())
            dir.listFiles()
                ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".json") }
                .orEmpty()
        }.sortedBy { it.name }
        return files
    }

    private fun loadFixture(file: File): SaluteFixture {
        val fixture = json.decodeFromString(SaluteFixture.serializer(), file.readText())
        assertEquals("${file.name}: formatVersion", FIXTURE_FORMAT_VERSION, fixture.formatVersion)
        return fixture
    }

    // ---- ASR replay --------------------------------------------------------------------

    @Test
    fun `committed ASR fixtures replay partial and final events through the fake ASR service`() {
        val files = fixtureFiles(ASR_PREFIX)
        assertTrue(
            "no asr_* fixtures found under test resources/$RECORD_DIR — the recorded-shape tier is empty",
            files.isNotEmpty(),
        )
        files.forEach { replayAsrFixture(it) }
    }

    private fun replayAsrFixture(file: File) = runBlocking {
        val fixture = loadFixture(file)
        assertEquals("${file.name}: kind", SaluteFixture.KIND_ASR, fixture.kind)
        val request = requireNotNull(fixture.asrRequest) { "${file.name}: ASR fixture must carry asrRequest" }

        val oauthServer = MockWebServer().apply { start() }
        oauthServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"replay-token","expires_in":3600}"""),
        )
        val fakeAsr = FakeSaluteAsrService()
        val harness = InProcessGrpc(fakeAsr)
        val tokenManager = TokenManager(
            null,
            OkHttpClient(),
            JarvisConfig(oauthEndpoint = oauthServer.url("/oauth").toString()),
            InMemoryVault(),
        ) { _ -> "replay" to "replay" }
        try {
            val asr = SberStreamingAsr(
                tokenManager,
                harness.channel,
                deadlineMs = 60_000,
                noSpeechTimeoutSec = request.noSpeechTimeoutSec,
            )
            val stream = asr.open()
            try {
                val expected = fixture.serverResponses.map { it.describeExpected(file.name) }
                val eventsJob = async { collectEvents(stream.events, count = expected.size) }
                yield() // collector subscribed (single-threaded runBlocking loop)
                fixture.serverResponses.forEach { it.emitTo(fakeAsr, file.name) }

                val actual = eventsJob.await().map { it.describeActual() }
                assertEquals("${file.name}: replayed client events", expected, actual)

                // The client's options frame matches the recorded request shape.
                val optionsFrame = fakeAsr.receivedRequests().firstOrNull { it.hasOptions() }
                assertNotNull("${file.name}: client must open with the options frame", optionsFrame)
                val options = optionsFrame!!.options
                assertEquals(request.audioEncoding, options.audioEncoding.name)
                assertEquals(request.sampleRate, options.sampleRate)
                assertEquals(request.language, options.language)
                assertEquals(request.model, options.model)
                assertEquals(request.enablePartialResults, options.enablePartialResults.enable)
                assertEquals(request.noSpeechTimeoutSec, options.noSpeechTimeout.seconds)
            } finally {
                stream.cancel()
            }
        } finally {
            harness.shutdown()
            oauthServer.shutdown()
        }
    }

    /** Normalized client event (Failed compared WITHOUT Throwable identity). */
    private fun AsrEvent.describeActual(): String = when (this) {
        is AsrEvent.Partial -> "Partial($text)"
        is AsrEvent.Final -> "Final($text)"
        is AsrEvent.Failed -> {
            val status = (cause as? StatusRuntimeException)?.status?.code
            "Failed(${status?.toString() ?: CLEAN_CLOSE})"
        }
    }

    private fun RecordedResponse.describeExpected(fixtureName: String): String = when (type) {
        RecordedResponse.TYPE_TRANSCRIPTION ->
            if (eou == true) {
                "Final(${texts.joinToString(" ").trim()})"
            } else {
                val text = texts.joinToString(" ").trim()
                assertTrue(
                    "$fixtureName: a partial response with no text would be silently dropped by the client",
                    text.isNotEmpty(),
                )
                "Partial($text)"
            }
        RecordedResponse.TYPE_ERROR -> {
            assertTrue("$fixtureName: error entry must carry a gRPC status", !status.isNullOrBlank())
            assertTrue(
                "$fixtureName: error entry message must be an exception CLASS NAME only (sanitization)",
                message.isNullOrBlank() || !message!!.contains(" "),
            )
            "Failed($status)"
        }
        RecordedResponse.TYPE_SERVER_CLOSED -> "Failed($CLEAN_CLOSE)"
        else -> error("$fixtureName: unexpected response type $type in an ASR fixture")
    }

    private fun RecordedResponse.emitTo(fake: FakeSaluteAsrService, fixtureName: String) {
        when (type) {
            RecordedResponse.TYPE_TRANSCRIPTION -> when {
                eou == true && texts.none { it.isNotBlank() } -> fake.emitNoSpeechTimeout()
                eou == true -> fake.emitFinal(texts.joinToString(" "))
                else -> {
                    val text = texts.joinToString(" ").trim()
                    assertTrue(
                        "$fixtureName: a partial response with no text would be silently dropped by the client",
                        text.isNotEmpty(),
                    )
                    fake.emitPartial(text)
                }
            }
            RecordedResponse.TYPE_ERROR -> fake.emitError(status.toStatus())
            RecordedResponse.TYPE_SERVER_CLOSED -> fake.completeStream()
            else -> error("$fixtureName: unexpected response type $type in an ASR fixture")
        }
    }

    // ---- TTS replay --------------------------------------------------------------------

    @Test
    fun `committed TTS fixtures replay chunk sequences through the fake TTS service`() {
        val files = fixtureFiles(TTS_PREFIX)
        assertTrue(
            "no tts_* fixtures found under test resources/$RECORD_DIR — the recorded-shape tier is empty",
            files.isNotEmpty(),
        )
        files.forEach { replayTtsFixture(it) }
    }

    private fun replayTtsFixture(file: File) = runBlocking {
        val fixture = loadFixture(file)
        assertEquals("${file.name}: kind", SaluteFixture.KIND_TTS, fixture.kind)
        val request = requireNotNull(fixture.ttsRequest) { "${file.name}: TTS fixture must carry ttsRequest" }
        val expectedChunks = fixture.serverResponses.map { response ->
            assertEquals(
                "${file.name}: TTS fixtures record chunk sequences",
                RecordedResponse.TYPE_CHUNK,
                response.type
            )
            requireNotNull(response.base64) { "${file.name}: chunk entry without base64 payload" }.decodeBase64()
        }

        val oauthServer = MockWebServer().apply { start() }
        oauthServer.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"replay-token","expires_in":3600}"""),
        )
        val fakeTts = FakeSaluteTtsService()
        val harness = InProcessGrpc(fakeTts)
        val tokenManager = TokenManager(
            null,
            OkHttpClient(),
            JarvisConfig(oauthEndpoint = oauthServer.url("/oauth").toString()),
            InMemoryVault(),
        ) { _ -> "replay" to "replay" }
        try {
            val tts = SaluteSpeechTts(tokenManager, harness.channel, deadlineMs = 20_000)
            val chunksJob = async {
                withTimeout(
                    5_000
                ) { tts.synthesizeStream(request.text, request.voiceInput).toList() }
            }
            val wireRequest = fakeTts.awaitRequest()
            expectedChunks.forEach { fakeTts.emitChunk(it) }
            delay(100) // bounded delivery pause — same pattern the P1 TTS suite uses
            fakeTts.completeStream()

            val chunks = chunksJob.await()
            assertEquals("${file.name}: chunk count", expectedChunks.size, chunks.size)
            assertEquals(
                "${file.name}: chunk bytes in order",
                expectedChunks.map { it.toList() },
                chunks.map { it.toList() }
            )

            // The wire request matches the recorded one.
            assertEquals(request.text, wireRequest.text)
            assertEquals(request.voiceWire, wireRequest.voice)
            assertEquals(request.audioEncoding, wireRequest.audioEncoding.name)
            assertEquals(request.language, wireRequest.language)
            assertEquals(request.contentType, wireRequest.contentType.name)
        } finally {
            harness.shutdown()
            oauthServer.shutdown()
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private fun String?.toStatus(): Status = runCatching {
        Status.fromCode(Status.Code.valueOf(this!!))
    }.getOrDefault(Status.UNKNOWN)

    private companion object {
        const val RECORD_DIR = "recorded"
        const val ASR_PREFIX = "asr_"
        const val TTS_PREFIX = "tts_"

        /** Marker for the client's clean-close failure (server half-close without EOU). */
        const val CLEAN_CLOSE = "clean-close"
    }
}
