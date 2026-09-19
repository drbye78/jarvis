package com.jarvis.assistant.speech

import io.grpc.ClientInterceptors
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.stub.AbstractStub
import io.grpc.stub.MetadataUtils
import java.util.concurrent.TimeUnit

/**
 * S-2: builds a SaluteSpeech stub with the shared plumbing both clients need
 * — the `authorization: Bearer <token>` metadata and the per-call deadline.
 *
 * ASR and TTS live in different proto packages (`grpc.recognition` vs
 * `grpc.synthesis`), so their generated stubs are unrelated types; the
 * [AbstractStub] upper bound plus a `newStub` factory is what lets ONE helper
 * serve both instead of duplicating the interceptor wiring in each client.
 *
 * The deadline stays at the call site by design: the ASR stream caps the whole
 * utterance (60 s) while TTS caps a single sentence (20 s), and those numbers
 * must not be conflated by a shared default.
 */
internal fun <T : AbstractStub<T>> bearerStub(
    channel: ManagedChannel,
    token: String,
    deadlineMs: Long,
    newStub: (io.grpc.Channel) -> T,
): T {
    val headers = Metadata().apply {
        put(
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
            "Bearer $token",
        )
    }
    val intercepted = ClientInterceptors.intercept(
        channel,
        MetadataUtils.newAttachHeadersInterceptor(headers),
    )
    return newStub(intercepted).withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
}
