package com.jarvis.assistant.llm

import com.jarvis.assistant.config.JarvisConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Real [CredentialValidator]: probes the Sber OAuth endpoint with the exact
 * request shape [TokenManager] uses (Basic auth + RqUID + scope form), so a
 * "Valid" here means the very next token fetch will succeed.
 *
 * Differences from a token fetch:
 *  - the response token is parsed but NEVER returned or cached;
 *  - short timeouts (a UI probe must not hang the "Checking…" row);
 *  - failures are classified ([CredentialCheck.Invalid] vs
 *    [CredentialCheck.Unverifiable]) instead of thrown.
 *
 * Exception hygiene follows the TokenManager contract: reasons carry status
 * codes and exception class names ONLY — raw response bodies are never
 * propagated (they can echo credential-adjacent material, and this class logs
 * via Timber).
 *
 * @param config supplies [JarvisConfig.oauthEndpoint].
 * @param httpClient base client (tests point it at MockWebServer and override
 *   the endpoint through [config]); production uses a shared lazy default.
 */
class OAuthCredentialValidator(
    private val config: JarvisConfig = JarvisConfig(),
    httpClient: OkHttpClient? = null,
) : CredentialValidator {

    private val json = Json { ignoreUnknownKeys = true }

    private val client: OkHttpClient = (httpClient ?: defaultClient).newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        .build()

    override suspend fun checkSalute(clientId: String, clientSecret: String): CredentialCheck =
        probe(OAuthScopes.SALUTE, clientId, clientSecret)

    override suspend fun checkGigaChat(clientId: String, clientSecret: String): CredentialCheck =
        probe(OAuthScopes.GIGACHAT, clientId, clientSecret)

    private suspend fun probe(scope: String, clientId: String, clientSecret: String): CredentialCheck =
        withContext(Dispatchers.IO) {
            if (clientId.isBlank() || clientSecret.isBlank()) {
                // Nothing to send — the caller guards this too; be defensive
                // and never call a blank pair "invalid".
                return@withContext CredentialCheck.Unverifiable("blank credentials")
            }

            val request = Request.Builder()
                .url(config.oauthEndpoint)
                .header("RqUID", UUID.randomUUID().toString())
                .header("Authorization", Credentials.basic(clientId, clientSecret))
                .post(
                    FormBody.Builder()
                        .add("scope", scope)
                        .add("grant_type", "client_credentials")
                        .build()
                )
                .build()

            try {
                client.newCall(request).await().use { response ->
                    when {
                        response.isSuccessful -> {
                            val body = response.body?.string().orEmpty()
                            val token = runCatching {
                                json.parseToJsonElement(body).jsonObject["access_token"]
                                    ?.jsonPrimitive?.content
                            }.getOrNull()
                            if (token.isNullOrBlank()) {
                                CredentialCheck.Unverifiable("no access_token in response")
                            } else {
                                CredentialCheck.Valid
                            }
                        }
                        // 4xx (except 429 rate-limit) = the server refused OUR
                        // request; for this fixed, well-formed probe shape the
                        // variable is the credential pair itself.
                        response.code in 400..499 && response.code != 429 ->
                            CredentialCheck.Invalid(response.code)
                        else -> CredentialCheck.Unverifiable("HTTP ${response.code}")
                    }
                }
            } catch (e: CancellationException) {
                // Barge-in / scope teardown must propagate, not masquerade as
                // a network verdict.
                throw e
            } catch (e: Exception) {
                Timber.i(e, "Credential probe failed: %s", e.javaClass.simpleName)
                CredentialCheck.Unverifiable(e.javaClass.simpleName)
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_S = 5L
        const val READ_TIMEOUT_S = 10L
        const val CALL_TIMEOUT_S = 15L

        /** One shared OkHttp instance for all probes (pools, dispatcher). */
        val defaultClient: OkHttpClient by lazy { OkHttpClient() }
    }
}
