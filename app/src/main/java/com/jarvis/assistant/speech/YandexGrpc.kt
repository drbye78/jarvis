package com.jarvis.assistant.speech

import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.stub.AbstractStub
import io.grpc.stub.MetadataUtils
import java.util.concurrent.TimeUnit

/**
 * Yandex SpeechKit v3 stub builder — the API-key counterpart of
 * [bearerStub].
 *
 * AUTH: Yandex Cloud authenticates a service-account API key with the literal
 * header `Authorization: Api-Key <secret>`. Unlike Salute's OAuth flow there
 * is no token to fetch or refresh (an API key does not expire), and — per the
 * owner's decision — NO `x-folder-id` is sent: the key already belongs to a
 * service account whose folder is implied. Both v3 clients therefore take the
 * key straight from the vault via a provider and never touch TokenManager.
 *
 * INBOUND SIZE: Yandex's streaming responses can exceed gRPC's 4 MB default
 * inbound limit; a synthesis of a long answer arrives as many audio chunks on
 * one logical stream. Raising the bound here (rather than per call site) keeps
 * the two clients consistent — see [MAX_INBOUND_MESSAGE_BYTES].
 *
 * The deadline stays at the call site by design, exactly as in [bearerStub]:
 * the ASR stream caps a whole utterance while TTS caps a single sentence, and
 * conflating them would be a bug.
 */
internal fun <T : AbstractStub<T>> yandexApiKeyStub(
    channel: ManagedChannel,
    apiKey: String,
    deadlineMs: Long,
    newStub: (io.grpc.Channel) -> T,
): T {
    val headers = Metadata().apply {
        put(
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
            // The scheme is case-sensitive on the wire ("Api-Key", not "API-Key").
            "Api-Key $apiKey",
        )
    }
    val intercepted = ClientInterceptors.intercept(
        channel,
        MetadataUtils.newAttachHeadersInterceptor(headers),
    )
    return newStub(intercepted)
        .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
        .withMaxInboundMessageSize(MAX_INBOUND_MESSAGE_BYTES)
}

/**
 * Inbound message ceiling for the Yandex v3 streams: 16 MB, comfortably above
 * gRPC's ~4 MB default that would otherwise abort a long synthesis mid-stream.
 * Kept far below "unbounded" so a misbehaving server cannot exhaust the heap.
 */
private const val MAX_INBOUND_MESSAGE_BYTES = 16 * 1024 * 1024
