package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SberTrust
import com.jarvis.assistant.util.withSberTrust
import io.grpc.ManagedChannel
import io.grpc.okhttp.OkHttpChannelBuilder
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Shared wiring for the live tier. NO production code was added for this:
 * every seam already exists ([TokenManager] vault/credentials overrides,
 * [JarvisConfig] endpoint defaults) — the live tier simply points them at the
 * real services instead of fakes.
 *
 * - Endpoints come from [JarvisConfig] defaults: ngw.devices.sberbank.ru
 *   (OAuth), gigachat.devices.sberbank.ru (chat/embeddings),
 *   smartspeech.sber.ru:443 (Salute gRPC).
 * - Tokens live in an [InMemoryVault]: they exist only for this JVM's
 *   lifetime and are never persisted or logged.
 */
internal fun liveOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .build()
    // The Sber endpoints chain to the Минцифры root CA, which is absent from
    // the host JDK trust store; without this every live call dies in the TLS
    // handshake ("PKIX path building failed") before reaching the service.
    // Production installs the same host-scoped trust via the shared client.
    .withSberTrust()

/** Real TLS channel to the configured Salute gRPC endpoint (host:port). */
internal fun saluteChannel(config: JarvisConfig = JarvisConfig()): ManagedChannel {
    val parts = config.saluteGrpcEndpoint.split(":")
    require(parts.size == 2) { "saluteGrpcEndpoint must be host:port" }
    // TLS is the DEFAULT negotiation for forAddress — do not "fix" this into
    // usePlaintext(); smartspeech.sber.ru:443 is a real TLS endpoint.
    // The SSLContext carries the same host-scoped Минцифры fallback the HTTP
    // clients use; the gRPC channel is handed the factory directly rather than
    // a trust manager (mirrors AppGraph's saluteChannel).
    return OkHttpChannelBuilder.forAddress(parts[0], parts[1].toInt())
        .sslSocketFactory(SberTrust.sslContext().socketFactory)
        .build()
}

/**
 * Real TLS channel to the configured Yandex SpeechKit v3 gRPC endpoint.
 *
 * Deliberately does NOT install [SberTrust]: Yandex chains to a public CA that
 * the host JDK already trusts, and scoping the Минцифры override to Sber hosts
 * is what keeps the two providers' trust independent (mirrors AppGraph's
 * yandexSttChannel / yandexTtsChannel).
 */
internal fun yandexChannel(endpoint: String): ManagedChannel {
    // Same host:port shape AppGraph's channels are built from, so the live tier
    // and production reach the identical target.
    require(endpoint.split(":").let { it.size == 2 && it[1].toIntOrNull() != null }) {
        "Yandex endpoint must be host:port, got $endpoint"
    }
    // TLS is the default negotiation for forTarget — do not "fix" this into
    // usePlaintext(); stt/tts.api.cloud.yandex.net:443 are real TLS endpoints.
    return OkHttpChannelBuilder.forTarget(endpoint).useTransportSecurity().build()
}

/**
 * [TokenManager] against the REAL OAuth endpoint with the local credentials
 * from [LiveSecrets] routed per scope (GigaChat vs Salute).
 */
internal fun liveTokenManager(secrets: LiveSecrets.Secrets): TokenManager =
    TokenManager(
        context = null,
        httpClient = liveOkHttpClient(),
        config = JarvisConfig(),
        vaultOverride = InMemoryVault(),
        credentials = { scope ->
            if (scope == LiveSecrets.SCOPE_GIGACHAT) {
                (secrets.gigachatClientId ?: "") to (secrets.gigachatClientSecret ?: "")
            } else {
                (secrets.saluteClientId ?: "") to (secrets.saluteClientSecret ?: "")
            }
        },
    )
