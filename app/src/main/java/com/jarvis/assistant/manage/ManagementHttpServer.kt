package com.jarvis.assistant.manage

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

/** The one body the spike server speaks. `status` has no default so it is always encoded. */
@Serializable
data class HealthResponse(val status: String)

/**
 * Minimal Ktor-Netty HTTPS server for the R13 §14 spike. It exposes ONLY
 * `GET /health` and is **not wired into the app**: nothing in the service, UI or
 * FGS constructs it, so it never runs in production. Its only purpose is to
 * prove on the target device that Ktor-Netty's `sslConnector` can bind and
 * terminate TLS on Android/API 29 — the riskiest unknown of the management
 * surface.
 *
 * Netty (not CIO): Ktor's CIO server engine throws on any HTTPS connector.
 * The connector binds to `127.0.0.1` by default (loopback only, reachable from
 * a host via `adb forward`), matching the LOCALHOST mode design.
 */
class ManagementHttpServer(
    private val tls: TlsMaterial,
    private val listenPort: Int,
    private val listenHost: String = DEFAULT_HOST,
) {
    private val server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> =
        embeddedServer(
            Netty,
            serverConfig {
                module { managementModule() }
            },
        ) {
            sslConnector(
                keyStore = tls.keyStore,
                keyAlias = tls.alias,
                keyStorePassword = { tls.keyPassword },
                privateKeyPassword = { tls.keyPassword },
            ) {
                host = listenHost
                port = listenPort
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

    private fun Application.managementModule() {
        install(ContentNegotiation) { json() }
        routing {
            get("/health") {
                call.respond(HealthResponse(status = "ok"))
            }
        }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        private const val STOP_GRACE_MILLIS = 200L
        private const val STOP_TIMEOUT_MILLIS = 1_000L
    }
}
