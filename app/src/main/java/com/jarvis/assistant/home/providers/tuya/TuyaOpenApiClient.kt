package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.net.HomeHttpTransport
import com.jarvis.assistant.home.net.HomeTransportException
import com.jarvis.assistant.home.net.HttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * The Tuya Cloud OpenAPI client: token lifecycle + HMAC-signed requests.
 *
 * Tuya is CLOUD-ONLY (there is no LAN path): every call goes to the project's
 * data-center host with `client_id` + a per-request `sign`. All provider shapes
 * stay in `home/providers/tuya/`; the core only sees [HomeResult].
 *
 * Pure of Android: the [transport], the credential readers and the clock are
 * injected, so the whole client is JVM-testable with a fake transport and a
 * fixed clock. [CancellationException] is always rethrown.
 *
 * The token is cached in memory with a safety margin; a business call that comes
 * back with Tuya's token-expired codes clears it and retries exactly ONCE.
 * Nothing here is persisted (a new process re-authenticates).
 */
class TuyaOpenApiClient(
    private val transport: HomeHttpTransport,
    private val baseUrl: () -> String,
    private val accessId: () -> String?,
    private val secret: () -> String?,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val nonce: () -> String = { defaultNonce() },
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private var cachedToken: String? = null
    private var expiresAtMs: Long = 0

    /** `GET /v1.0/token?grant_type=1` → the current access token. */
    suspend fun token(): HomeResult<String> {
        val now = nowMs()
        cachedToken?.let { if (now < expiresAtMs) return HomeResult.Ok(it) }
        return authenticate()
    }

    /** A signed business GET. [query] is canonicalized (sorted) before signing. */
    suspend fun get(path: String, query: List<Pair<String, String>> = emptyList()): HomeResult<JsonElement> =
        executeAuthed { bearer -> signedRequest("GET", path, query, null, bearer) }

    /** A signed business POST with a JSON [body]. */
    suspend fun post(
        path: String,
        body: String,
        query: List<Pair<String, String>> = emptyList(),
    ): HomeResult<JsonElement> = executeAuthed { bearer -> signedRequest("POST", path, query, body, bearer) }

    // ------------------------------------------------------------------
    // Token lifecycle.
    // ------------------------------------------------------------------

    private suspend fun authenticate(): HomeResult<String> {
        val id = accessId()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return HomeResult.Err(HomeError.NOT_CONFIGURED, "tuya access id is not configured")
        val key = secret()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return HomeResult.Err(HomeError.NOT_CONFIGURED, "tuya access secret is not configured")
        val query = listOf("grant_type" to "1")
        val result = signedRequest("GET", TOKEN_PATH, query, null, accessToken = "", id = id, secret = key)
        val element = when (result) {
            is HomeResult.Err -> return result
            is HomeResult.Ok -> result.value
        }
        val token = TuyaPayloads.accessToken(element)
            ?: return HomeResult.Err(HomeError.AUTH, "tuya token response had no access token")
        cachedToken = token
        expiresAtMs = nowMs() + (TuyaPayloads.expireSeconds(element) ?: DEFAULT_EXPIRE_SECONDS) * 1000L -
            EXPIRY_MARGIN_MS
        return HomeResult.Ok(token)
    }

    /** Run an authed call, re-authenticating once if the token was rejected. */
    private suspend fun executeAuthed(
        call: suspend (bearer: String) -> HomeResult<JsonElement>,
    ): HomeResult<JsonElement> {
        val first = token()
        val bearer = when (first) {
            is HomeResult.Err -> return first
            is HomeResult.Ok -> first.value
        }
        val attempt = call(bearer)
        if (attempt is HomeResult.Err && attempt.error == HomeError.AUTH) {
            cachedToken = null
            val retryToken = when (val refreshed = token()) {
                is HomeResult.Err -> return refreshed
                is HomeResult.Ok -> refreshed.value
            }
            return call(retryToken)
        }
        return attempt
    }

    // ------------------------------------------------------------------
    // Signing + transport.
    // ------------------------------------------------------------------

    private suspend fun signedRequest(
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: String?,
        accessToken: String,
        id: String = accessId()?.trim().orEmpty(),
        secret: String = secret()?.trim().orEmpty(),
    ): HomeResult<JsonElement> {
        if (id.isEmpty() || secret.isEmpty()) {
            return HomeResult.Err(HomeError.NOT_CONFIGURED, "tuya credentials are not configured")
        }
        val url = TuyaSigning.canonicalUrl(path, query)
        val t = nowMs()
        val nonceValue = nonce()
        val toSign = TuyaSigning.stringToSign(method, url, body)
        val sign = TuyaSigning.sign(id, secret, t, nonceValue, accessToken, toSign)
        val headers = buildMap {
            put("client_id", id)
            put("sign", sign)
            put("sign_method", TuyaSigning.SIGN_METHOD)
            put("t", t.toString())
            put("nonce", nonceValue)
            if (accessToken.isNotEmpty()) put("access_token", accessToken)
            if (body != null) put("Content-Type", JSON_CONTENT_TYPE)
        }
        val response = try {
            if (method == "GET") {
                transport.get(baseUrl().trimEnd('/') + url, headers)
            } else {
                transport.post(baseUrl().trimEnd('/') + url, headers, body.orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HomeTransportException) {
            return HomeResult.Err(HomeError.UNREACHABLE, e.message)
        } catch (_: Exception) {
            return HomeResult.Err(HomeError.UNREACHABLE)
        }
        return classify(response)
    }

    /** HTTP status → typed error, else the parsed envelope. */
    private fun classify(response: HttpResponse): HomeResult<JsonElement> = when {
        response.status == 401 || response.status == 403 -> HomeResult.Err(HomeError.AUTH)
        response.status !in 200..299 -> HomeResult.Err(HomeError.FAILED, "tuya http ${response.status}")
        else -> TuyaPayloads.result(response.body, json)
    }

    private companion object {
        const val TOKEN_PATH = "/v1.0/token"
        const val JSON_CONTENT_TYPE = "application/json"
        const val DEFAULT_EXPIRE_SECONDS = 7200L

        /** Re-auth this long before Tuya's own expiry to avoid a race at the boundary. */
        const val EXPIRY_MARGIN_MS = 60_000L

        fun defaultNonce(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
