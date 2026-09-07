package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.speech.asr.AsrEvent
import com.jarvis.assistant.speech.asr.SberStreamingAsr
import com.jarvis.assistant.speech.tts.SaluteSpeechTts
import io.grpc.ManagedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

/**
 * P2.3 recording pipeline — LOCAL ONLY (REMEDIATION_PLAN Phase 2, owner
 * decision #1). Run via `./gradlew :app:recordSaluteFixtures` (the main()
 * self-gates: exit 0 with a logged skip message when credentials are absent).
 *
 * Makes three live Salute calls and writes SANITIZED fixtures under
 * `app/src/test/resources/recorded/` (the task passes the directory):
 *
 *  1. OAuth token fetch (sanity — the token is never written anywhere);
 *  2. ASR round trip on 1 s of SYNTHETIC SILENCE (`no_speech_timeout` = 3 s
 *     so the server ends the utterance promptly);
 *  3. TTS round trip on the fixed probe phrase [TTS_PROBE_TEXT].
 *
 * PRIVACY CONTRACT (see SaluteFixtures.kt): only SERVER responses are
 * recorded — never request headers, tokens, RqUIDs or timestamps. The input
 * is synthetic silence / a fixed non-personal phrase, so no user audio or
 * text can enter a fixture. ASR error events are reduced to the gRPC status
 * code and the exception CLASS NAME. The committed fixtures are what CI
 * replays through the in-process gRPC fakes (SaluteFixtureReplayTest);
 * re-running this task overwrites the same two files, so re-recording shows
 * up as a clean diff.
 *
 * Deterministic fixture names (timestamps deliberately omitted):
 *   asr_silence_ru.json / tts_mila_probe.json
 */
private const val ASR_FIXTURE_FILE = "asr_silence_ru.json"
private const val TTS_FIXTURE_FILE = "tts_mila_probe.json"

/** 1 s of 16 kHz s16le mono silence, sent in 100 ms frames. */
private const val ASR_SILENCE_FRAMES = 10
private const val ASR_FRAME_BYTES = 3_200
private const val ASR_NO_SPEECH_TIMEOUT_SEC = 3L

/** 24 kHz 16-bit mono ≈ 48 KB/s — refuse to commit more than ~4 s of audio. */
private const val MAX_TTS_AUDIO_BYTES = 192_000

/** Console helper: this is a Gradle JavaExec with no Timber forest planted —
 *  the console IS the output channel (detekt bans kotlin.io.println; this is
 *  PrintStream). Credentials are never passed here. */
private fun out(message: String) {
    System.out.println(message)
}

private val fixtureJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    val outputDir = args.getOrNull(0)?.let(::File) ?: run {
        out("ERROR: usage: recordSaluteFixtures <outputDir>")
        exitProcess(2)
    }

    if (!LiveSecrets.hasSalute) {
        out("recordSaluteFixtures SKIPPED: Salute credentials not present — nothing recorded.")
        out("Provide jarvis.salute.clientId / jarvis.salute.clientSecret in local.secrets.properties " +
                "(copy local.secrets.properties.example) or via JARVIS_SALUTE_* env vars.")
        exitProcess(0)
    }

    var failures = 0
    val written = mutableListOf<String>()

    runBlocking {
        val config = JarvisConfig()
        val tokenManager = liveTokenManager(LiveSecrets.secrets)
        val channel = saluteChannel(config)
        try {
            // --- 1. OAuth sanity (fails fast and honestly; value never printed) ---
            runCatching { tokenManager.getSaluteToken() }
                .onSuccess { out("Salute OAuth: OK (token acquired, ${it.length} chars, value never printed)") }
                .onFailure {
                    out("Salute OAuth FAILED: ${it.javaClass.simpleName} — check jarvis.salute.* credentials")
                    exitProcess(1)
                }

            // --- 2. ASR capture ---
            try {
                val responses = captureAsrResponses(tokenManager, channel)
                require(responses.any { it.type == RecordedResponse.TYPE_TRANSCRIPTION }) {
                    "ASR capture produced no transcription event (got: ${responses.map { it.type }}) — " +
                        "not a healthy protocol recording; nothing written"
                }
                val fixture = SaluteFixture(
                    kind = SaluteFixture.KIND_ASR,
                    description = "Recorded from the LIVE Salute ASR service with 1 s of synthetic silence " +
                        "(no_speech_timeout=${ASR_NO_SPEECH_TIMEOUT_SEC}s). An empty transcript is the expected " +
                        "outcome for silence — the fixture pins protocol health, not transcription.",
                    provenance = FixtureProvenance(
                        recorded = true,
                        syntheticInput = true,
                        sanitization = "Server responses only: no credentials or request headers, no timestamps, " +
                            "no user audio/text; error entries carry gRPC status + exception class name only.",
                    ),
                    asrRequest = RecordedAsrRequest(noSpeechTimeoutSec = ASR_NO_SPEECH_TIMEOUT_SEC),
                    serverResponses = responses,
                )
                writeFixture(outputDir, ASR_FIXTURE_FILE, fixture, written)
                out("ASR capture: ${responses.size} client-visible events from silence input")
            } catch (e: Exception) {
                failures++
                out("ASR capture FAILED: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }

            // --- 3. TTS capture ---
            try {
                val (request, chunks) = captureTtsChunks(tokenManager, channel)
                val fixture = SaluteFixture(
                    kind = SaluteFixture.KIND_TTS,
                    description = "Recorded from the LIVE Salute TTS service: the fixed probe phrase " +
                        "\"${TTS_PROBE_TEXT}\" (voice $TTS_PROBE_WIRE_VOICE on the wire), " +
                        "${chunks.size} audio chunk(s).",
                    provenance = FixtureProvenance(
                        recorded = true,
                        syntheticInput = true,
                        sanitization = "Server responses only: no credentials or request headers, no timestamps. " +
                            "Audio is the service's own voice speaking the fixed probe phrase — no user data.",
                    ),
                    ttsRequest = request,
                    serverResponses = chunks.map { RecordedResponse(type = RecordedResponse.TYPE_CHUNK, base64 = it.toBase64()) },
                )
                writeFixture(outputDir, TTS_FIXTURE_FILE, fixture, written)
                out("TTS capture: ${chunks.size} chunk(s), ${chunks.sumOf { it.size }} bytes")
            } catch (e: Exception) {
                failures++
                out("TTS capture FAILED: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
        } finally {
            channel.shutdownNow()
        }
    }

    if (written.isEmpty()) {
        out("recordSaluteFixtures: nothing recorded ($failures capture(s) failed). Fixtures unchanged.")
        exitProcess(1)
    }
    out("recordSaluteFixtures: wrote ${written.size} sanitized fixture(s):")
    written.forEach { out("  $it") }
    out("Review the diff and commit — CI replays these through the in-process gRPC fakes.")
    if (failures > 0) exitProcess(1)
}

private fun writeFixture(outputDir: File, name: String, fixture: SaluteFixture, written: MutableList<String>) {
    outputDir.mkdirs()
    val file = File(outputDir, name)
    file.writeText(fixtureJson.encodeToString(SaluteFixture.serializer(), fixture))
    val userDir = File(System.getProperty("user.dir") ?: ".")
    written.add("${file.relativeToOrNull(userDir) ?: file.absolutePath} (${file.length()} bytes)")
}

/**
 * One live ASR round trip on synthetic silence; returns the sanitized
 * server-response scripts reproducing the client-visible events.
 */
private suspend fun captureAsrResponses(
    tokenManager: TokenManager,
    channel: ManagedChannel,
): List<RecordedResponse> {
    val asr = SberStreamingAsr(
        tokenManager,
        channel,
        deadlineMs = 30_000,
        noSpeechTimeoutSec = ASR_NO_SPEECH_TIMEOUT_SEC,
    )
    val stream = asr.open()
    val events = mutableListOf<AsrEvent>()
    val terminal = CompletableDeferred<Unit>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val collector = scope.launch {
        stream.events.collect { event ->
            synchronized(events) { events.add(event) }
            if (event is AsrEvent.Final || event is AsrEvent.Failed) terminal.complete(Unit)
        }
    }
    try {
        repeat(ASR_SILENCE_FRAMES) {
            stream.send(ByteArray(ASR_FRAME_BYTES)) // synthetic silence, never stored
            delay(100)
        }
        stream.finish()
        withTimeout(45_000) { terminal.await() }
    } finally {
        collector.cancel()
        stream.cancel()
        scope.cancel()
    }
    return synchronized(events) { events.toList() }.map { it.toRecordedResponse() }
}

/** One live TTS round trip on the fixed probe phrase. */
private suspend fun captureTtsChunks(
    tokenManager: TokenManager,
    channel: ManagedChannel,
): Pair<RecordedTtsRequest, List<ByteArray>> {
    val tts = SaluteSpeechTts(tokenManager, channel, deadlineMs = 20_000)
    val chunks = withTimeout(45_000) { tts.synthesizeStream(TTS_PROBE_TEXT, voice = "Mila").toList() }
    val total = chunks.sumOf { it.size }
    check(total in 1..MAX_TTS_AUDIO_BYTES) {
        "TTS payload of $total bytes is outside 1..$MAX_TTS_AUDIO_BYTES — refusing to write an oversized fixture"
    }
    return RecordedTtsRequest(
        text = TTS_PROBE_TEXT,
        voiceInput = "Mila",
        voiceWire = TTS_PROBE_WIRE_VOICE,
    ) to chunks
}
