package com.jarvis.assistant.api

import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.contracts.TokenProvider
import com.jarvis.assistant.util.SecurePrefs
import kotlinx.coroutines.Dispatchers
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
 * Dual Sber OAuth [TokenProvider].
 *
 * Both GigaChat and Salute Speech authenticate against the same Sber OAuth 2.0
 * token endpoint using the `client_credentials` grant. The only differences are
 * the client id/secret pair and the requested `scope`.
 *
 * Tokens are cached in [SecurePrefs] together with their absolute expiry
 * timestamp (epoch millis). A cached token is reused until less than 60 seconds
 * of validity remain, at which point it is refreshed.
 *
 * Errors are surfaced by throwing (no silent nulls): missing credentials,
 * non-2xx responses, or a malformed token payload all raise.
 */
class TokenManager(
    context: Context,
    private val httpClient: OkHttpClient
) : TokenProvider {

    private val appContext = context.applicationContext
    private val prefs = SecurePrefs.get(appContext)

    private val json = Json { ignoreUnknownKeys = true }

    // Sber OAuth 2.0 token endpoint (shared by GigaChat and Salute Speech).
    private val oauthEndpoint = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth"

    // Refresh window: refresh when fewer than this many ms of validity remain.
    private val REFRESH_THRESHOLD_MS = 60_000L

    override suspend fun getGigaChatToken(): String = getToken(
        cacheKey = KEY_GIGACHAT_TOKEN,
        expiryKey = KEY_GIGACHAT_EXPIRY,
        clientId = BuildConfig.GIGACHAT_CLIENT_ID,
        clientSecret = BuildConfig.GIGACHAT_CLIENT_SECRET,
        scope = SCOPE_GIGACHAT
    )

    override suspend fun getSaluteToken(): String = getToken(
        cacheKey = KEY_SALUTE_TOKEN,
        expiryKey = KEY_SALUTE_EXPIRY,
        clientId = BuildConfig.SALUTE_CLIENT_ID,
        clientSecret = BuildConfig.SALUTE_CLIENT_SECRET,
        scope = SCOPE_SALUTE
    )

    private suspend fun getToken(
        cacheKey: String,
        expiryKey: String,
        clientId: String,
        clientSecret: String,
        scope: String
    ): String = withContext(Dispatchers.IO) {
        val cached = prefs.getString(cacheKey, null)
        val expiry = prefs.getLong(expiryKey, 0L)
        val now = System.currentTimeMillis()
        if (!cached.isNullOrBlank() && (expiry - now) > REFRESH_THRESHOLD_MS) {
            return@withContext cached
        }

        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw IllegalStateException(
                "Missing OAuth client credentials for scope='$scope'. " +
                    "Set ${scope}_CLIENT_ID / ${scope}_CLIENT_SECRET in local.properties."
            )
        }

        val body = FormBody.Builder()
            .add("scope", scope)
            .add("grant_type", "client_credentials")
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .build()

        val request = Request.Builder()
            .url(oauthEndpoint)
            .addHeader("RqUID", UUID.randomUUID().toString())
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(body)
            .build()

        val response = httpClient.newCall(request).execute()
        val raw = try {
            response.body?.string().orEmpty()
        } finally {
            response.close()
        }

        if (!response.isSuccessful) {
            Timber.e("Token refresh failed: $raw")
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
            else -> System.currentTimeMillis() + 60 * 60 * 1000L // default 1h
        }

        prefs.edit()
            .putString(cacheKey, token)
            .putLong(expiryKey, expiryMillis)
            .apply()

        token
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
