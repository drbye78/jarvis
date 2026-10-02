package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.grpc.yandextts.AudioFormatOptions
import com.jarvis.assistant.grpc.yandextts.Hints
import com.jarvis.assistant.grpc.yandextts.RawAudio
import com.jarvis.assistant.grpc.yandextts.SynthesizerGrpc
import com.jarvis.assistant.grpc.yandextts.UtteranceSynthesisRequest
import com.jarvis.assistant.grpc.yandextts.UtteranceSynthesisResponse
import com.jarvis.assistant.speech.yandexApiKeyStub
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Status
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Yandex SpeechKit v3 TTS over the server-streaming
 * `Synthesizer.UtteranceSynthesis` RPC.
 *
 * AUTH: a service-account API key read lazily per synthesis via
 * [apiKeyProvider] — the vault is never touched at graph-construction time.
 *
 * VOICE SELECTION: the request carries its settings as a repeated `Hints`
 * list, each entry a scalar. Up to three hints are emitted:
 * - the speaker name ([Hints.setVoice]) — always,
 * - optionally a pronunciation ROLE ([Hints.setRole]), and
 * - optionally a speaking RATE ([Hints.setSpeed]).
 *
 * Because the [TtsClient] contract passes a single `voice` string, all three
 * travel in-band as `"<voice>[:<role>][@<speed>]"` (e.g. `marina:whisper@1.5`)
 * — packed and unpacked by [YandexVoiceSpec], the single definition of that
 * convention. A value with no `:` (or an empty role after it) yields the voice
 * hint alone and lets the service apply its default role; a `1.0`/absent speed
 * yields no speed hint. This keeps the provider-neutral contract unchanged
 * while still exposing Yandex's role and speed features.
 *
 * FAIL-CLOSED ROLE: an undocumented voice/role pair is a HARD service error,
 * not a fallback, so the role is re-validated against
 * [VoiceCatalog.validRoleFor] here — the runtime chokepoint that stops a stale
 * pref (or a hand-crafted spec) from failing the whole synthesis.
 *
 * SAMPLE-RATE CONSTRAINT (this is the correctness-critical part): Yandex
 * defaults to **22050 Hz LINEAR16 PCM wrapped in a WAV header**. The entire
 * playback chain instead assumes 24 kHz HEADERLESS PCM —
 * [com.jarvis.assistant.contracts.AudioSpec.TTS] is the contract AudioTrack is
 * opened with, and the AEC far-end tap resamples 24 kHz → 16 kHz. Yandex is
 * one of the few providers that lets the rate be requested explicitly, so
 * [synthesizeStream] ALWAYS pins it: an omitted `output_audio_spec` would play
 * ~9 % slow and feed the echo canceller a wrongly-rated reference signal.
 * Unlike [SaluteSpeechTts] — whose proto cannot express a rate at all — there
 * is nothing to detect here, only to request correctly.
 *
 * CANCELLATION: the RPC runs under a cancellable [Context]; a downstream
 * cancellation (barge-in, session end) aborts it server-side instead of
 * leaking the synthesis, and a gRPC [Status.Code.CANCELLED] is treated as a
 * normal close (`awaitClose` cancels the RPC on the way out).
 */
class YandexSpeechTts(
    private val apiKeyProvider: () -> String,
    private val channel: ManagedChannel,
    private val deadlineMs: Long = 20_000,
) : TtsClient {

    private companion object {
        /**
         * PCM rate the whole pipeline assumes
         * ([com.jarvis.assistant.contracts.AudioSpec.TTS]). Requested
         * explicitly on every synthesis — see the class KDoc.
         */
        const val TTS_SAMPLE_RATE_HERTZ = 24_000L
    }

    override fun synthesizeStream(text: String, voice: String): Flow<ByteArray> = channelFlow {
        val cancellableContext = Context.current().withCancellation()

        val producer = launch(Dispatchers.IO) {
            try {
                val request = UtteranceSynthesisRequest.newBuilder()
                    .setText(text)
                    .addAllHints(hintsFor(voice))
                    .setOutputAudioSpec(
                        AudioFormatOptions.newBuilder().setRawAudio(
                            RawAudio.newBuilder()
                                .setAudioEncoding(RawAudio.AudioEncoding.LINEAR16_PCM)
                                .setSampleRateHertz(TTS_SAMPLE_RATE_HERTZ),
                        ),
                    )
                    .build()

                // A chunk is delivered through a spawned child so the
                // non-suspending gRPC callback can apply backpressure, and the
                // close path joins the children spawned so far — otherwise a
                // synchronous close() from onCompleted can outrun a pending
                // send() and drop the final audio chunk. gRPC delivers an
                // observer's callbacks serially on one thread, so no new child
                // can appear after onCompleted/onError. (Same reasoning as
                // SaluteSpeechTts; see its long comment for the failed
                // monitor-based alternative.)
                val childJobs = ArrayList<Job>()

                val responseObserver = object : StreamObserver<UtteranceSynthesisResponse> {
                    override fun onNext(value: UtteranceSynthesisResponse) {
                        val bytes = value.audioChunk.data
                        if (!bytes.isEmpty) {
                            childJobs += this@channelFlow.launch {
                                this@channelFlow.send(bytes.toByteArray())
                            }
                        }
                    }

                    override fun onError(t: Throwable) {
                        // The async stub delivers StatusRuntimeException (not
                        // StatusException); check both so an expected barge-in
                        // cancel does not surface as a flow failure.
                        val code = (t as? io.grpc.StatusException)?.status?.code
                            ?: (t as? io.grpc.StatusRuntimeException)?.status?.code
                        val failure = if (code == Status.Code.CANCELLED) null else t
                        if (failure == null) {
                            this@channelFlow.launch {
                                childJobs.joinAll()
                                close()
                            }
                        } else {
                            Timber.e(t, "Yandex TTS stream error")
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

                val stub = yandexApiKeyStub(
                    this@YandexSpeechTts.channel,
                    apiKeyProvider(),
                    deadlineMs,
                    SynthesizerGrpc::newStub,
                )
                cancellableContext.run {
                    stub.utteranceSynthesis(request, responseObserver)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                Timber.e(e, "Yandex TTS network error")
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

    /**
     * Unpacks the `"<voice>[:<role>][@<speed>]"` convention ([YandexVoiceSpec],
     * the single definition of the packing) into the `Hints` list the request
     * needs. `Hints` is a scalar `oneof`, so each field is its own entry:
     * the voice hint is always present; the role hint only when the voice
     * DOCUMENTS that role (fail-closed); the speed hint only when it is set and
     * differs from the service default.
     */
    private fun hintsFor(voice: String): List<Hints> {
        val split = YandexVoiceSpec.split(voice)
        val hints = ArrayList<Hints>(3)
        hints += Hints.newBuilder().setVoice(split.voice).build()

        val role = VoiceCatalog.validRoleFor(split.voice, split.role)
        if (role != null) {
            hints += Hints.newBuilder().setRole(role).build()
        } else if (split.role != null) {
            // Content-free: never log the voice or the role text. A stale pref
            // is dropped rather than sent as a hard service error.
            Timber.w("Yandex TTS: dropped an undocumented role for the selected voice")
        }

        val speed = split.speed
        if (speed != null && speed != YandexVoiceSpec.DEFAULT_SPEED) {
            hints += Hints.newBuilder().setSpeed(speed.toDouble()).build()
        }
        return hints
    }
}
