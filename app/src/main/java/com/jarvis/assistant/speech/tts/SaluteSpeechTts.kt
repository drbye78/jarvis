package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.grpc.synthesis.SmartSpeechGrpc
import com.jarvis.assistant.grpc.synthesis.SynthesisRequest
import com.jarvis.assistant.grpc.synthesis.SynthesisResponse
import com.jarvis.assistant.llm.TokenManager
import io.grpc.ClientInterceptors
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

// The TtsClient contract itself lives in TtsClient.kt (pure JVM) so the
// audio-etiquette lane can compile against it without gRPC.

/**
 * SaluteSpeech TTS over gRPC server-streaming Synthesize.
 *
 * Fixes vs. the original:
 * - The RPC runs under a CANCELLABLE [Context]; when the downstream collector
 *   is cancelled (barge-in, session end) `awaitClose` cancels the context and
 *   gRPC aborts the call. The original leaked every interrupted synthesis
 *   until the server finished on its own.
 * - A gRPC deadline ([deadlineMs]) caps each sentence, so a hung synthesis
 *   can no longer wedge a session in SPEAKING forever.
 *
 * SAMPLE-RATE CONSTRAINT (audit fix, honest documentation): the
 * `SynthesisRequest` proto carries NO sample-rate field (see
 * `app/src/main/proto/synthesis.proto` — text/encoding/language/content_type/
 * voice only), so the PCM output rate is pinned by the VOICE, not the
 * request. The whole playback chain assumes 24 kHz ([contracts.AudioSpec.TTS]:
 * AudioTrack writes, and the AEC far-end tap's 24 kHz → 16 kHz resampler).
 * Known-good voices encode the rate in their pool ID (`May_24000`); a
 * FREE-TEXT voice ID naming a different rate (e.g. an `*_8000` pool voice)
 * would synthesize at that rate and play at the wrong pitch. The Settings
 * free-text entry point is validated only by the «Проверить голос» probe —
 * as a defensive net, [synthesizeStream] cross-checks the server-reported
 * `audio_duration` against the received byte count and logs a content-free
 * warning when the implied rate is not ~24 kHz (detection only — the audio
 * itself cannot be re-pitched meaningfully after the fact).
 */
class SaluteSpeechTts(
    private val tokenManager: TokenManager,
    private val channel: ManagedChannel,
    private val deadlineMs: Long = 20_000,
) : TtsClient {

    private companion object {
        /**
         * The PCM sample rate every downstream consumer assumes
         * ([com.jarvis.assistant.contracts.AudioSpec.TTS]). Tolerance 10% —
         * `audio_duration` is server-rounded, so a small deviation is noise.
         */
        const val EXPECTED_PCM_SAMPLE_RATE = 24_000
        const val RATE_TOLERANCE = 0.10
    }

    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = channelFlow {
        val cancellableContext = Context.current().withCancellation()

        val producer = launch(Dispatchers.IO) {
            try {
                val token = tokenManager.getSaluteToken()
                val headers = Metadata().apply {
                    put(
                        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                        "Bearer $token"
                    )
                }
                val intercepted = ClientInterceptors.intercept(
                    this@SaluteSpeechTts.channel,
                    MetadataUtils.newAttachHeadersInterceptor(headers),
                )
                val stub = SmartSpeechGrpc.newStub(intercepted)
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)

                val request = SynthesisRequest.newBuilder()
                    .setText(text)
                    .setVoice(mapVoice(voice))
                    .setAudioEncoding(SynthesisRequest.AudioEncoding.PCM_S16LE)
                    .setLanguage("ru-RU")
                    .setContentType(SynthesisRequest.ContentType.TEXT)
                    .build()

                // P3.5 (fix of the documented latent race): a chunk is
                // delivered via a spawned CHILD coroutine
                // (`launch { send(bytes) }`, needed for backpressure from the
                // non-suspending gRPC callback), so a synchronous `close()`
                // from onCompleted could outrun a still-undelivered child and
                // silently drop the final audio chunk.
                //
                // Fix: gRPC delivers an observer's callbacks SERIALLY on one
                // thread (guaranteed by the async stub), so no new child can
                // appear after onCompleted/onError. The close path therefore
                // joins the children spawned so far and closes only after —
                // a Job list is the whole mechanism. (An earlier monitor-
                // based "completion gate" hung here: its deferred close
                // could never fire once the downstream consumer cancelled
                // the producer scope first.)
                // Note: if the DOWNSTREAM consumer cancels first, the
                // producer scope is cancelled and these launches are no-ops —
                // the channel is already dead; awaitClose below still runs
                // and cancels the RPC server-side, which is the contract the
                // "downstream cancel" test pins.
                val childJobs = ArrayList<Job>()

                // Defensive rate check state (per synthesis stream; the async
                // stub delivers observer callbacks serially on one thread).
                var receivedBytes = 0L
                var rateWarned = false

                val responseObserver = object : StreamObserver<SynthesisResponse> {
                    override fun onNext(value: SynthesisResponse) {
                        val bytes = value.data.toByteArray()
                        // N5: bridge the gRPC callback (non-suspend) to the
                        // channelFlow producer scope. send() suspends on
                        // backpressure so audio chunks are never silently dropped.
                        if (bytes.isNotEmpty()) {
                            receivedBytes += bytes.size
                            // Rate cross-check (see the class KDoc): the proto
                            // cannot pin the PCM rate, so validate the voice's
                            // actual output against the 24 kHz the player and
                            // AEC tap assume. Content-free log (no voice id).
                            if (!rateWarned && value.hasAudioDuration()) {
                                val durationSec = value.audioDuration.seconds +
                                    value.audioDuration.nanos / 1e9
                                if (durationSec > 0) {
                                    val impliedRate =
                                        (receivedBytes / 2) / durationSec
                                    val deviation =
                                        kotlin.math.abs(impliedRate - EXPECTED_PCM_SAMPLE_RATE)
                                    if (deviation > EXPECTED_PCM_SAMPLE_RATE * RATE_TOLERANCE) {
                                        rateWarned = true
                                        Timber.w(
                                            "TTS PCM rate mismatch: implied %.0f Hz vs expected %d Hz — " +
                                                "the selected voice synthesizes at a different rate; " +
                                                "playback pitch will be wrong (pick a 24 kHz voice)",
                                            impliedRate,
                                            EXPECTED_PCM_SAMPLE_RATE,
                                        )
                                    }
                                }
                            }
                            childJobs += this@channelFlow.launch { this@channelFlow.send(bytes) }
                        }
                    }

                    override fun onError(t: Throwable) {
                        // The async stub delivers StatusRuntimeException (not
                        // StatusException) — check both or an expected barge-in
                        // cancel would surface as a flow failure downstream.
                        val code = (t as? io.grpc.StatusException)?.status?.code
                            ?: (t as? io.grpc.StatusRuntimeException)?.status?.code
                        val failure = if (code == io.grpc.Status.Code.CANCELLED) null else t
                        if (failure == null) {
                            this@channelFlow.launch {
                                childJobs.joinAll()
                                close()
                            } // expected on barge-in
                        } else {
                            Timber.e(t, "TTS stream error")
                            this@channelFlow.launch {
                                childJobs.joinAll()
                                close(failure)
                            }
                        }
                    }

                    override fun onCompleted() {
                        this@channelFlow.launch {
                            childJobs.joinAll()
                            close()
                        }
                    }
                }

                cancellableContext.run {
                    stub.synthesize(request, responseObserver)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Timber.e(e, "TTS network error")
                if (!cancellableContext.isCancelled()) close(e)
            } catch (e: Exception) {
                if (!cancellableContext.isCancelled()) close(e)
            }
        }

        awaitClose {
            producer.cancel()
            cancellableContext.cancel(Status.CANCELLED.asException())
        }
    }

    private fun mapVoice(voice: String): String = when (voice.lowercase()) {
        "mila" -> "May_24000" // 24 kHz female Russian voice
        else -> voice
    }
}
