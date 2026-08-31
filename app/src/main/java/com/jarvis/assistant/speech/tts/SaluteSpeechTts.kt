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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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
 */
class SaluteSpeechTts(
    private val tokenManager: TokenManager,
    private val channel: ManagedChannel,
    private val deadlineMs: Long = 20_000,
) : TtsClient {

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

                val responseObserver = object : StreamObserver<SynthesisResponse> {
                    override fun onNext(value: SynthesisResponse) {
                        val bytes = value.data.toByteArray()
                        // N5: bridge the gRPC callback (non-suspend) to the
                        // channelFlow producer scope. send() suspends on
                        // backpressure so audio chunks are never silently dropped.
                        if (bytes.isNotEmpty()) this@channelFlow.launch { this@channelFlow.send(bytes) }
                    }

                    override fun onError(t: Throwable) {
                        val cancelled = (t as? io.grpc.StatusException)
                            ?.status?.code == io.grpc.Status.Code.CANCELLED
                        if (cancelled) {
                            close() // expected on barge-in
                        } else {
                            Timber.e(t, "TTS stream error")
                            close(t)
                        }
                    }

                    override fun onCompleted() {
                        close()
                    }
                }

                cancellableContext.run {
                    stub.synthesize(request, responseObserver)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
