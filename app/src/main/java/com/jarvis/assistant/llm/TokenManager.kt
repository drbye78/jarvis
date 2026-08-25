package com.jarvis.assistant.llm

import android.content.Context
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
 * never trigger duplicate token requests.
 */
class TokenManager(
    context: Context,
    private val httpClient: OkHttpClient,
    private val config: JarvisConfig = JarvisConfig(),
) {
    private val appContext = context.applicationContext
    private val prefs = SecurePrefs.get(appContext)
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshMutex = Mutex()

    suspend fun getGigaChatToken(): String = getToken(
        cacheKey = KEY_GIGACHAT_TOKEN,
        expiryKey = KEY_GIGACHAT_EXPIRY,
        clientId = BuildConfig.GIGACHAT_CLIENT_ID,
        clientSecret = BuildConfig.GIGACHAT_CLIENT_SECRET,
        scope = SCOPE_GIGACHAT,
    )

    suspend fun getSaluteToken(): String = getToken(
        cacheKey = KEY_SALUTE_TOKEN,
        expiryKey = KEY_SALUTE_EXPIRY,
        clientId = BuildConfig.SALUTE_CLIENT_ID,
        clientSecret = BuildConfig.SALUTE_CLIENT_SECRET,
        scope = SCOPE_SALUTE,
    )

    private suspend fun getToken(
        cacheKey: String,
        expiryKey: String,
        clientId: String,
        clientSecret: String,
        scope: String,
    ): String {
        val cached = prefs.getString(cacheKey, null)
        val expiry = prefs.getLong(expiryKey, 0L)
        val now = System.currentTimeMillis()
        if (!cached.isNullOrBlank() && (expiry - now) > config.oauthRefreshThresholdMs) {
            return cached
        }
        // Serialize refreshes across concurrent callers.
        return refreshMutex.withLock {
            // Re-check inside the lock: another coroutine may have refreshed.
            val fresh = prefs.getString(cacheKey, null)
            val freshExpiry = prefs.getLong(expiryKey, 0L)
            if (!fresh.isNullOrBlank() && (freshExpiry - now) > config.oauthRefreshThresholdMs) {
                return@withLock fresh
            }
            fetchToken(cacheKey, expiryKey, clientId, clientSecret, scope)
        }
    }

    private suspend fun fetchToken(
        cacheKey: String,
        expiryKey: String,
        clientId: String,
        clientSecret: String,
        scope: String,
    ): String = withContext(Dispatchers.IO) {
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

        httpClient.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                Timber.e("Token refresh failed: HTTP ${response.code}: $raw")
                throw RuntimeException(
                    "OAuth token request failed (HTTP ${response.code}) for scope='$scope': $raw"
                )
            }

            val parsed = try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                throw RuntimeException("OAuth response is not valid JSON: $raw", e)
            }

            val token = parsed["access_token"]?.jsonPrimitive?.content
                ?: throw RuntimeException("OAuth response missing 'access_token': $raw")

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
