package com.jarvis.assistant.llm

import android.content.Context
import android.content.SharedPreferences
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.UUID

/**
 * Dual Sber OAuth token provider (GigaChat + SaluteSpeech).
 *
 * Tokens are cached in [SecurePrefs] with absolute expiry; a cached token is
 * reused until [JarvisConfig.oauthRefreshThresholdMs] of validity remains.
 * Refresh is serialized behind a [Mutex] so concurrent ASR/TTS/LLM callers
 * never trigger duplicate token requests; the freshness timestamp is captured
 * INSIDE the lock so a caller that waited on the mutex judges the winner's
 * token, not its own stale pre-lock clock.
 *
 * Token fetches run through the cancellable HTTP primitive ([await]) — barge-in
 * during a refresh aborts the in-flight request.
 *
 * Exception messages carry status code + category ONLY. Raw OAuth response
 * bodies are never baked into exceptions: malformed responses can echo token
 * material, and these exceptions reach the rotating file log via Timber.
 *
 * @param context required only when [prefsOverride] is not supplied.
 * @param prefsOverride test seam: in-memory SharedPreferences for JVM tests.
 * @param credentials test seam: scope → client credentials; defaults to the
 *   BuildConfig values baked from local.properties.
 */
class TokenManager(
    context: Context?,
    private val httpClient: OkHttpClient,
    private val config: JarvisConfig = JarvisConfig(),
    prefsOverride: SharedPreferences? = null,
    private val credentials: (scope: String) -> Pair<String, String> = { scope ->
        when (scope) {
            SCOPE_GIGACHAT -> BuildConfig.GIGACHAT_CLIENT_ID to BuildConfig.GIGACHAT_CLIENT_SECRET
            else -> BuildConfig.SALUTE_CLIENT_ID to BuildConfig.SALUTE_CLIENT_SECRET
        }
    },
) {
    private val prefs: SharedPreferences =
        prefsOverride ?: SecurePrefs.get(
            requireNotNull(context) { "context required when prefsOverride not supplied" }
                .applicationContext
        )

    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    suspend fun getGigaChatToken(): String = getToken(
        cacheKey = KEY_GIGACHAT_TOKEN,
        expiryKey = KEY_GIGACHAT_EXPIRY,
        scope = SCOPE_GIGACHAT,
    )

    suspend fun getSaluteToken(): String = getToken(
        cacheKey = KEY_SALUTE_TOKEN,
        expiryKey = KEY_SALUTE_EXPIRY,
        scope = SCOPE_SALUTE,
    )

    private suspend fun getToken(
        cacheKey: String,
        expiryKey: String,
        scope: String,
    ): String {
        val cached = prefs.getString(cacheKey, null)
        val expiry = prefs.getLong(expiryKey, 0L)
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
            val fresh = prefs.getString(cacheKey, null)
            val freshExpiry = prefs.getLong(expiryKey, 0L)
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
                    "Set them in local.properties or choose the OpenAI-compatible " +
                    "provider in Settings."
            )
        }

        val body = FormBody.Builder()
            .add("scope", scope)
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(config.oauthEndpoint)
            .header("RqUID", UUID.randomUUID().toString())
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
                else -> System.currentTimeMillis() + 60 * 60 * 1000L
            }

            prefs.edit()
                .putString(cacheKey, token)
                .putLong(expiryKey, expiryMillis)
                .apply()

            token
        }
    }

    private companion object {
        const val KEY_GIGACHAT_TOKEN = "gigachat_token"
        const val KEY_GIGACHAT_EXPIRY = "gigachat_token_expiry"
        const val KEY_SALUTE_TOKEN = "salute_token"
        const val KEY_SALUTE_EXPIRY = "salute_token_expiry"
        const val SCOPE_GIGACHAT = "GIGACHAT_API_PERS"
        const val SCOPE_SALUTE = "SALUTE_SPEECH_PERS"
    }
}
