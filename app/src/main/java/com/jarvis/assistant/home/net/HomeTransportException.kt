package com.jarvis.assistant.home.net

import java.io.IOException

/**
 * A network-class failure from the smart-home transport edge (DNS, TCP connect
 * refused, TLS, socket/read timeout).
 *
 * The [HomeHttpTransport] contract cannot return an error envelope, so the
 * transport signals an unreachable server by throwing this. The provider backend
 * catches it and maps it to [com.jarvis.assistant.home.HomeError.UNREACHABLE] —
 * it is deliberately NOT swallowed into a fake `200`. Cancellation is never
 * wrapped in this (the transport rethrows `CancellationException` untouched so
 * barge-in aborts the socket).
 */
class HomeTransportException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
