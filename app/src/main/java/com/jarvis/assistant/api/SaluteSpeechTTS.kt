package com.jarvis.assistant.api

import com.jarvis.assistant.contracts.TokenProvider
import com.jarvis.assistant.grpc.synthesis.SmartSpeechGrpc
import com.jarvis.assistant.grpc.synthesis.SynthesisRequest
import com.jarvis.assistant.grpc.synthesis.SynthesisResponse
import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext

/**
 * Salute Speech TTS client over gRPC (smartspeech.sber.ru:443).
 *
 * Emits 24 kHz / 16-bit / mono / little-endian PCM chunks as a [Flow]<[ByteArray]>
 * (matches [com.jarvis.assistant.contracts.AudioSpec.TTS]), consumed by
 * [com.jarvis.assistant.audio.StreamingAudioTrackPlayer].
 *
 * @param tokenProvider provides the Sber OAuth bearer token for the gRPC call.
 */
class SaluteSpeechTTS(
    private val tokenProvider: TokenProvider,
    private val channel: ManagedChannel
) {

    /**
     * Stream synthesized speech as 24 kHz / 16-bit / mono PCM chunks.
     *
     * @param text  text to synthesize.
     * @param voice Salute voice id (default "Mila" -> mapped to "May_24000").
     */
    fun synthesizeStream(
        text: String,
        voice: String = "Mila"
    ): Flow<ByteArray> = callbackFlow {
        val token = withContext(Dispatchers.IO) { tokenProvider.getSaluteToken() }

        try {
            val headers = Metadata().apply {
                put(
                    Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer $token"
                )
            }
            val interceptedChannel = ClientInterceptors.intercept(
                this@SaluteSpeechTTS.channel,
                MetadataUtils.newAttachHeadersInterceptor(headers)
            )
            val stub = SmartSpeechGrpc.newStub(interceptedChannel)

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
                    if (bytes.isNotEmpty()) trySend(bytes)
                }

                override fun onError(t: Throwable) {
                    close(t)
                }

                override fun onCompleted() {
                    close()
                }
            }

            stub.synthesize(request, responseObserver)
            awaitClose { /* No channel shutdown — managed by AppGraph */ }
        } catch (e: Throwable) {
            close(e)
        }
    }

    private fun mapVoice(voice: String): String = when (voice.lowercase()) {
        "mila" -> "May_24000" // 24 kHz female Russian voice
        else -> voice
    }
}
