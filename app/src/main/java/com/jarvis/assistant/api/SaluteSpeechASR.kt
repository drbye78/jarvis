package com.jarvis.assistant.api

import com.jarvis.assistant.contracts.AsrClient
import com.jarvis.assistant.contracts.AsrResult
import com.jarvis.assistant.contracts.TokenProvider
import com.jarvis.assistant.grpc.recognition.OptionalBool
import com.jarvis.assistant.grpc.recognition.RecognitionOptions
import com.jarvis.assistant.grpc.recognition.RecognitionRequest
import com.jarvis.assistant.grpc.recognition.RecognitionResponse
import com.jarvis.assistant.grpc.recognition.SmartSpeechGrpc
import com.jarvis.assistant.grpc.recognition.Transcription
import com.google.protobuf.ByteString
import io.grpc.ClientInterceptors
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.okhttp.OkHttpChannelBuilder
import io.grpc.stub.MetadataUtils
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.TimeUnit

/**
 * Salute Speech streaming ASR client over gRPC (smartspeech.sber.ru:443).
 *
 * Implements [AsrClient.recognizeStreaming]: sends a single [RecognitionOptions]
 * frame followed by the PCM audio chunks on the bidi `Recognize` stream, then
 * collects the final [Transcription] (marked by `eou == true`) as the result.
 *
 * Audio contract: 16 kHz / 16-bit / mono / little-endian PCM (matches
 * [com.jarvis.assistant.contracts.AudioSpec.MIC]).
 */
class SaluteSpeechASR(private val tokenProvider: TokenProvider) : AsrClient {

    private val endpoint = "smartspeech.sber.ru:443"

    override suspend fun recognizeStreaming(pcm: ByteArray): AsrResult = withContext(Dispatchers.IO) {
        if (pcm.isEmpty()) return@withContext AsrResult.NoSpeech

        val token = tokenProvider.getSaluteToken()
        val channel = OkHttpChannelBuilder.forTarget(endpoint).useTransportSecurity().build()
        try {
            withTimeout(60_000L) {
                suspendCancellableCoroutine { cont ->
                    val transcript = StringBuilder()
                    var settled = false

                    fun settle(result: AsrResult) {
                        if (settled) return
                        settled = true
                        if (cont.isActive) cont.resumeWith(Result.success(result))
                    }

                    val headers = Metadata().apply {
                        put(
                            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                            "Bearer $token"
                        )
                    }
                    val interceptedChannel = ClientInterceptors.intercept(
                        channel,
                        MetadataUtils.newAttachHeadersInterceptor(headers)
                    )
                    val stub = SmartSpeechGrpc.newStub(interceptedChannel)

                    val responseObserver = object : StreamObserver<RecognitionResponse> {
                        override fun onNext(value: RecognitionResponse) {
                            if (!value.hasTranscription()) return
                            val t: Transcription = value.transcription
                            val text = t.resultsList.joinToString(" ") { it.text }.trim()
                            if (text.isNotBlank()) {
                                transcript.setLength(0)
                                transcript.append(text)
                            }
                            if (t.eou) {
                                val final = transcript.toString().trim()
                                settle(
                                    if (final.isNotBlank()) AsrResult.Success(final)
                                    else AsrResult.NoSpeech
                                )
                            }
                        }

                        override fun onError(t: Throwable) {
                            settle(AsrResult.Failure(t))
                        }

                        override fun onCompleted() {
                            val final = transcript.toString().trim()
                            settle(
                                if (final.isNotBlank()) AsrResult.Success(final)
                                else AsrResult.NoSpeech
                            )
                        }
                    }

                    val requestObserver = stub.recognize(responseObserver)
                    cont.invokeOnCancellation {
                        runCatching { requestObserver.onError(Status.CANCELLED.asException()) }
                    }

                    requestObserver.onNext(
                        RecognitionRequest.newBuilder()
                            .setOptions(
                                RecognitionOptions.newBuilder()
                                    .setAudioEncoding(RecognitionOptions.AudioEncoding.PCM_S16LE)
                                    .setSampleRate(16_000)
                                    .setLanguage("ru-RU")
                                    .setModel("general")
                                    .setEnablePartialResults(OptionalBool.newBuilder().setEnable(true).build())
                                    .build()
                            )
                            .build()
                    )

                    // Stream the PCM in ~0.5s chunks.
                    val chunkSize = 16_000
                    var offset = 0
                    while (offset < pcm.size) {
                        val end = minOf(offset + chunkSize, pcm.size)
                        requestObserver.onNext(
                            RecognitionRequest.newBuilder()
                                .setAudioChunk(ByteString.copyFrom(pcm, offset, end - offset))
                                .build()
                        )
                        offset = end
                    }
                    requestObserver.onCompleted()
                }
            }
        } catch (e: TimeoutCancellationException) {
            return@withContext AsrResult.Failure(e)
        } finally {
            runCatching { channel.shutdown().awaitTermination(2, TimeUnit.SECONDS) }
        }
    }
}