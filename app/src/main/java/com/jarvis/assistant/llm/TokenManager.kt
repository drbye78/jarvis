package com.jarvis.assistant.llm

import android.content.Context
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.util.CredentialsStore
import com.jarvis.assistant.util.KeystoreVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

/**
 * Dual Sber OAuth token provider (GigaChat + SaluteSpeech).
 *
 * Tokens are cached in the [SecretVault] (Keystore-encrypted in production)
 * with absolute expiry; a cached token is reused until
 * [JarvisConfig.oauthRefreshThresholdMs] of validity remains. Refresh is
 * serialized behind a [Mutex] so concurrent ASR/TTS/LLM callers never trigger
 * duplicate token requests; the freshness timestamp is captured INSIDE the
 * lock so a caller that waited on the mutex judges the winner's token, not
 * its own stale pre-lock clock.
 *
 * Token fetches run through the cancellable HTTP primitive ([await]) — barge-in
 * during a refresh aborts the in-flight request.
 *
 * Exception messages carry status code + category ONLY. Raw OAuth response
 * bodies are never baked into exceptions: malformed responses can echo token
 * material, and these exceptions reach the rotating file log via Timber.
 *
 * @param context required only when [vaultOverride] is not supplied.
 * @param vaultOverride test seam: in-memory vault for JVM tests.
 * @param credentials test seam: scope → client credentials; defaults to the
 *   values stored in [CredentialsStore] (entered by the user in Settings).
 */
class TokenManager(
    context: Context?,
    private val httpClient: OkHttpClient,
    private val config: JarvisConfig = JarvisConfig(),
    vaultOverride: SecretVault? = null,
    private val credentials: (scope: String) -> Pair<String, String> = { scope ->
        // Audit #30: peek() — outside the app lifecycle (JVM tests without a
        // constructed store) this yields empty credentials, which fail the
        // request honestly (HTTP 401) instead of crashing a lateinit lookup.
        val store = CredentialsStore.peek()
        when (scope) {
            SCOPE_GIGACHAT -> (store?.gigaChatClientId ?: "") to (store?.gigaChatClientSecret ?: "")
            else -> (store?.saluteClientId ?: "") to (store?.saluteClientSecret ?: "")
        }
    },
) {
    private val vault: SecretVault =
        vaultOverride ?: KeystoreVault.get(
            requireNotNull(context) { "context required when vaultOverride not supplied" }
                .applicationContext
        )

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    suspend fun getGigaChatToken(): String = getToken(
        cacheKey = SecretVault.KEY_GIGACHAT_TOKEN,
        expiryKey = SecretVault.KEY_GIGACHAT_EXPIRY,
        scope = SCOPE_GIGACHAT,
    )

    suspend fun getSaluteToken(): String = getToken(
        cacheKey = SecretVault.KEY_SALUTE_TOKEN,
        expiryKey = SecretVault.KEY_SALUTE_EXPIRY,
        scope = SCOPE_SALUTE,
    )

    /**
     * Force a token refresh after the user changes credentials in Settings.
     * Clears both cached Sber tokens so the next request re-authenticates.
     */
    fun invalidate() {
        vault.remove(SecretVault.KEY_GIGACHAT_TOKEN)
        vault.remove(SecretVault.KEY_GIGACHAT_EXPIRY)
        vault.remove(SecretVault.KEY_SALUTE_TOKEN)
        vault.remove(SecretVault.KEY_SALUTE_EXPIRY)
    }

    private suspend fun getToken(
        cacheKey: String,
        expiryKey: String,
        scope: String,
    ): String {
        val cached = vault.getString(cacheKey)
        val expiry = vault.getString(expiryKey)?.toLongOrNull() ?: 0L
        if (!cached.isNullOrBlank() &&
            (expiry - System.currentTimeMillis()) > config.oauthRefreshThresholdMs
        ) {
            return cached
        }
        // Serialize refreshes across concurrent callers.
        return refreshMutex.withLock {
            // Re-check inside the lock: another coroutine may have refreshed.
            // Timestamp captured HERE — after any lock wait — so the
            // double-check judges validity against the current clock.
            val now = System.currentTimeMillis()
            val fresh = vault.getString(cacheKey)
            val freshExpiry = vault.getString(expiryKey)?.toLongOrNull() ?: 0L
            if (!fresh.isNullOrBlank() && (freshExpiry - now) > config.oauthRefreshThresholdMs) {
                return@withLock fresh
            }
            fetchToken(cacheKey, expiryKey, scope)
        }
    }

    private suspend fun fetchToken(
        cacheKey: String,
        expiryKey: String,
        scope: String,
    ): String = withContext(Dispatchers.IO) {
        val (clientId, clientSecret) = credentials(scope)
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw IllegalStateException(
                "Missing OAuth client credentials for scope='$scope'. " +
                    "Set them in Settings (Настройки) or choose the OpenAI-compatible " +
                    "provider in Settings."
            )
        }

        // Sber OAuth requires HTTP Basic auth: base64(client_id:client_secret).
        // OkHttp's Credentials.basic is platform-independent (works on the
        // JVM unit-test runtime and on Android minSdk 30 alike).
        val authHeader = Credentials.basic(clientId, clientSecret)
        val body = FormBody.Builder()
            .add("scope", scope)
            .add("grant_type", "client_credentials")
            .build()

        val request = Request.Builder()
            .url(config.oauthEndpoint)
            .header("RqUID", UUID.randomUUID().toString())
            .header("Authorization", authHeader)
            .post(body)
            .build()

        httpClient.newCall(request).await().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Sanitized: status + category only, NEVER the raw body —
                // malformed responses can contain token material and these
                // messages reach the rotating file log via Timber.
                Timber.e("Token refresh failed: HTTP %d for scope=%s", response.code, scope)
                throw RuntimeException(
                    "OAuth token request failed (HTTP ${response.code}) for scope='$scope'"
                )
            }

            val parsed = try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                throw RuntimeException(
                    "OAuth response is not valid JSON (HTTP ${response.code}) for scope='$scope'", e
                )
            }

            val token = parsed["access_token"]?.jsonPrimitive?.content
                ?: throw RuntimeException(
                    "OAuth response missing 'access_token' (HTTP ${response.code}) for scope='$scope'"
                )

            val expiresIn = parsed["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
            val expiresAt = parsed["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()
            val expiryMillis = when {
                expiresAt != null -> expiresAt * 1000L // Sber returns epoch seconds
                expiresIn != null -> System.currentTimeMillis() + expiresIn * 1000L
                else -> {
                    // Audit #14: the response carried NO expiry hint. The old
                    // 1-hour blind cache could serve a long-dead token (Sber
                    // tokens are short-lived); the conservative fallback is
                    // 5 minutes + a warning, so an odd provider response shape
                    // degrades into an early refresh, never a stale-token hour.
                    Timber.w(
                        "OAuth response carried neither expires_at nor expires_in — " +
                            "caching token for the conservative %d ms fallback",
                        FALLBACK_EXPIRY_MS,
                    )
                    System.currentTimeMillis() + FALLBACK_EXPIRY_MS
                }
            }

            vault.putString(cacheKey, token)
            vault.putString(expiryKey, expiryMillis.toString())

            token
        }
    }

    private companion object {
        const val SCOPE_GIGACHAT = "GIGACHAT_API_PERS"
        const val SCOPE_SALUTE = "SALUTE_SPEECH_PERS"

        /** Token cache lifetime when the OAuth response has no expiry hint (audit #14). */
        const val FALLBACK_EXPIRY_MS = 5 * 60 * 1000L
    }
}
