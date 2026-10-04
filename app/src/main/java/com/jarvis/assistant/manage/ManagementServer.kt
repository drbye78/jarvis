package com.jarvis.assistant.manage

import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing

/**
 * Ktor-Netty HTTPS lifecycle for the R13 §14 management surface. Supersedes the
 * `ManagementHttpServer` spike (which only served `GET /health`).
 *
 * Netty, never CIO: Ktor's CIO server engine throws on any HTTPS connector.
 * The connector is ALWAYS bound to a specific interface — `127.0.0.1` for the
 * LOCALHOST mode or the injected LAN address for LAN — never `0.0.0.0`; the
 * caller supplies [bindHost]. TLS material comes from [TlsCertFactory]
 * (BouncyCastle self-signed, PKCS#12 keystore).
 *
 * The route tree is injected as [configure] so the transport stays independent
 * of the surface: production installs a `ManagementRoutes`, while the device
 * diagnostic installs a trivial `/health` route. Shutdown is bounded so a
 * wedged engine cannot hang the caller.
 *
 * This class is constructible but deliberately NOT wired into `AppGraph`/the
 * FGS yet — that is a later lane.
 */
class ManagementServer(
    private val tls: TlsMaterial,
    private val port: Int,
    private val bindHost: String = LOOPBACK_HOST,
    private val configure: Routing.() -> Unit = {},
) {
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            Netty,
            serverConfig { module { configureServer() } },
        ) {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.alias,
                keyStorePassword = { tls.keyPassword },
                privateKeyPassword = { tls.keyPassword },
            ) {
                host = bindHost
                port = this@ManagementServer.port
            }
        }

    /** Binds the connector and returns immediately; call [stop] to close it. */
    fun start() {
        server.start(wait = false)
    }

    /** Bounded shutdown so a wedged engine cannot hang the caller. */
    fun stop() {
        server.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
    }

    private fun Application.configureServer() {
        routing { configure() }
    }

    companion object {
        /** LOCALHOST mode binds loopback only; reachable via an authorized `adb forward`. */
        const val LOOPBACK_HOST = "127.0.0.1"
        private const val STOP_GRACE_MILLIS = 200L
        private const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}
