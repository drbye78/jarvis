package com.jarvis.assistant.integration

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.llm.TokenManager
import com.jarvis.assistant.util.InMemoryVault
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

/** Real TLS channel to the configured Salute gRPC endpoint (host:port). */
internal fun saluteChannel(config: JarvisConfig = JarvisConfig()): ManagedChannel {
    val parts = config.saluteGrpcEndpoint.split(":")
    require(parts.size == 2) { "saluteGrpcEndpoint must be host:port" }
    // TLS is the DEFAULT negotiation for forAddress — do not "fix" this into
    // usePlaintext(); smartspeech.sber.ru:443 is a real TLS endpoint.
    return OkHttpChannelBuilder.forAddress(parts[0], parts[1].toInt()).build()
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
