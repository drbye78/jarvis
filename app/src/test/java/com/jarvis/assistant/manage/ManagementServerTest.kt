package com.jarvis.assistant.manage

import com.jarvis.assistant.FakeSharedPreferences
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.InMemoryVault
import com.jarvis.assistant.util.SecretVault
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * End-to-end JVM test of the REAL [ManagementServer] over loopback HTTPS.
 *
 * It stands up the full stack (BouncyCastle TLS + Ktor-Netty + the frozen
 * `/api/v1` routes over [ManagementCore]) and drives it with OkHttp configured
 * with a trust-all [SSLContext] because the certificate is self-signed.
 *
 * OkHttp rather than `java.net.http.HttpClient`: `java.net.http` is not part of
 * Android's `android.jar`, which is the unit-test compile bootclasspath, so it
 * cannot be imported by an Android unit test. OkHttp is already a dependency,
 * is JVM-tested elsewhere, and allows overriding `Host`/`Origin`/`Sec-Fetch-Site`
 * so the hardening guards can be exercised like a browser would.
 *
 * No Android imports: the vault is [InMemoryVault], the prefs are
 * [FakeSharedPreferences], and the SPA is a canned [AssetSource].
 */
class ManagementServerTest {

    private lateinit var server: ManagementServer
    private lateinit var client: OkHttpClient
    private lateinit var prefs: AppPrefs
    private lateinit var core: ManagementCore
    private lateinit var vault: InMemoryVault

    private var port: Int = 0

    private val password = "correct horse battery staple"

    @Before
    fun setUp() {
        vault = InMemoryVault()
        vault.putString(
            SecretVault.KEY_MANAGEMENT_PASSWORD,
            PasswordHasher.encodeRecord(PasswordHasher.hash(password.toCharArray(), Argon2Params.LOW_MEMORY)),
        )
        prefs = AppPrefs(context = null, vaultOverride = vault, prefsOverride = FakeSharedPreferences())
        prefs.managementMode = "localhost"
        core = ManagementCore(
            prefs = prefs,
            bindings = ManagementBindings(vault),
            vault = vault,
            appVersion = "0.2.2",
            pendingPolicies = { emptySet() },
        )
        val auth = ManagementAuth(
            readStoredSecret = { vault.getString(SecretVault.KEY_MANAGEMENT_PASSWORD) },
            writeStoredSecret = { vault.putString(SecretVault.KEY_MANAGEMENT_PASSWORD, it) },
        )
        val activation = ManagementActivation()
        activation.start(ManagementMode.LOCALHOST)
        val routes = ManagementRoutes(
            core = core,
            auth = auth,
            sessions = SessionStore(),
            activation = activation,
            assets = AssetSource { path -> if (path == ManagementRoutes.INDEX_HTML) INDEX_HTML.toByteArray() else null },
            tlsFingerprint = "AA:BB:CC",
            allowedHosts = ManagementRoutes.DEFAULT_ALLOWED_HOSTS,
        )
        port = freePort()
        server = ManagementServer(TlsCertFactory.generate(), port, ManagementServer.LOOPBACK_HOST) {
            routes.install(this)
        }
        server.start()
        client = trustAllClient()
        awaitReady()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `unauthenticated status is rejected`() {
        val response = request("GET", "/api/v1/status")

        assertEquals(401, response.code)
        assertTrue(response.body.contains("\"code\":\"unauthorized\""))
    }

    @Test
    fun `login issues a hardened session cookie and authenticated status succeeds`() {
        val login = login()
        assertEquals(201, login.code)
        val cookie = login.headers["Set-Cookie"].orEmpty()
        assertTrue(cookie.contains("HttpOnly"))
        assertTrue(cookie.contains("Secure"))
        assertTrue(cookie.contains("SameSite=Strict"))

        val status = request("GET", "/api/v1/status", headers = cookieHeaders(sessionId(login)))
        assertEquals(200, status.code)
        assertTrue(status.body.contains("\"service\":\"jarvis\""))
        assertTrue(status.body.contains("\"mode\":\"localhost\""))
        assertTrue(status.body.contains("\"sha256Fingerprint\":\"AA:BB:CC\""))
        assertEquals("nosniff", status.headers["X-Content-Type-Options"].orEmpty())
        assertEquals("DENY", status.headers["X-Frame-Options"].orEmpty())
        assertEquals("no-referrer", status.headers["Referrer-Policy"].orEmpty())
    }

    @Test
    fun `setting round trips and secret settings are forbidden`() {
        val id = sessionId(login())

        val write = request(
            "PUT",
            "/api/v1/settings/memoryEnabled",
            headers = unsafeHeaders(id),
            body = """{"value":false}""",
        )
        assertEquals(200, write.code)
        assertTrue(write.body.contains("\"policy\":\"LIVE\""))
        assertFalse(prefs.memoryEnabled)

        val read = request("GET", "/api/v1/settings/memoryEnabled", headers = cookieHeaders(id))
        assertEquals(200, read.code)
        assertTrue(read.body.contains("\"value\":false"))

        val secret = request("GET", "/api/v1/settings/yandexApiKey", headers = cookieHeaders(id))
        assertEquals(403, secret.code)
    }

    @Test
    fun `settings payload advertises enum options and omits them for plain keys`() {
        val id = sessionId(login())

        val list = request("GET", "/api/v1/settings", headers = cookieHeaders(id))
        assertEquals(200, list.code)
        val rows = (Json.parseToJsonElement(list.body) as JsonArray).map { it.jsonObject }
        val enumRow = rows.first { it["key"]?.jsonPrimitive?.content == "providerType" }
        assertEquals(
            listOf("gigachat", "openai", "yandex"),
            enumRow["options"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        val plainRow = rows.first { it["key"]?.jsonPrimitive?.content == "memoryEnabled" }
        assertNull("a non-enum key must not carry options", plainRow["options"])

        val single = request("GET", "/api/v1/settings/providerType", headers = cookieHeaders(id))
        assertEquals(200, single.code)
        val singleObject = Json.parseToJsonElement(single.body).jsonObject
        assertEquals(
            listOf("gigachat", "openai", "yandex"),
            singleObject["options"]?.jsonArray?.map { it.jsonPrimitive.content },
        )

        val plainSingle = request("GET", "/api/v1/settings/memoryEnabled", headers = cookieHeaders(id))
        assertEquals(200, plainSingle.code)
        assertNull(Json.parseToJsonElement(plainSingle.body).jsonObject["options"])
    }

    @Test
    fun `an out-of-vocabulary enum write is rejected and writes nothing`() {
        val id = sessionId(login())
        val before = prefs.weatherProvider

        val response = request(
            "PUT",
            "/api/v1/settings/weatherProvider",
            unsafeHeaders(id),
            """{"value":"not-a-provider"}""",
        )

        assertEquals(400, response.code)
        assertEquals(before, prefs.weatherProvider)
    }

    @Test
    fun `export then import round trips a configuration change`() {
        val id = sessionId(login())
        request("PUT", "/api/v1/settings/memoryEnabled", unsafeHeaders(id), """{"value":false}""")
        assertFalse(prefs.memoryEnabled)

        val exported = request(
            "POST",
            "/api/v1/export",
            unsafeHeaders(id),
            buildJsonObject {
                put("passphrase", "export-pass")
                put("includeSecrets", false)
            }.toString(),
        )
        assertEquals(200, exported.code)
        assertEquals("application/octet-stream", exported.headers["Content-Type"].orEmpty())
        val artifact = exported.body

        request("PUT", "/api/v1/settings/memoryEnabled", unsafeHeaders(id), """{"value":true}""")
        assertTrue(prefs.memoryEnabled)

        val imported = request(
            "POST",
            "/api/v1/import",
            unsafeHeaders(id),
            buildJsonObject {
                put("passphrase", "export-pass")
                put("data", artifact)
            }.toString(),
        )
        assertEquals(200, imported.code)
        assertTrue(imported.body.contains("\"errors\":[]"))
        assertFalse("the imported document re-applied memoryEnabled=false", prefs.memoryEnabled)
    }

    @Test
    fun `a bad host is rejected with 400`() {
        val response = request("GET", "/api/v1/status", headers = mapOf("Host" to "evil.test"))

        assertEquals(400, response.code)
        assertTrue(response.body.contains("\"code\":\"bad_host\""))
    }

    @Test
    fun `a cross origin unsafe request is rejected with 403`() {
        val id = sessionId(login())

        val response = request(
            "PUT",
            "/api/v1/settings/memoryEnabled",
            headers = mapOf(
                "Cookie" to "jarvis_mgmt=$id",
                "Origin" to "https://evil.test",
            ),
            body = """{"value":true}""",
        )

        assertEquals(403, response.code)
        assertTrue(response.body.contains("\"code\":\"cross_origin\""))
    }

    @Test
    fun `a sec fetch site none unsafe request is allowed`() {
        val id = sessionId(login())

        val response = request(
            "PUT",
            "/api/v1/settings/memoryEnabled",
            headers = mapOf(
                "Cookie" to "jarvis_mgmt=$id",
                "Sec-Fetch-Site" to "none",
            ),
            body = """{"value":true}""",
        )

        assertEquals(200, response.code)
        assertTrue(prefs.memoryEnabled)
    }

    @Test
    fun `deleting the current session revokes it`() {
        val id = sessionId(login())

        val delete = request("DELETE", "/api/v1/sessions/current", headers = unsafeHeaders(id))
        assertEquals(204, delete.code)

        val after = request("GET", "/api/v1/status", headers = cookieHeaders(id))
        assertEquals(401, after.code)
    }

    @Test
    fun `the static spa is served without auth`() {
        val response = request("GET", "/")

        assertEquals(200, response.code)
        assertTrue(response.body.contains("Jarvis"))
        assertTrue(response.headers["Content-Security-Policy"].orEmpty().isNotEmpty())
    }

    // ------------------------------------------------------------------

    private fun login(): HttpResult = request(
        "POST",
        "/api/v1/sessions",
        headers = mapOf("Sec-Fetch-Site" to "same-origin"),
        body = buildJsonObject { put("password", password) }.toString(),
    )

    private fun sessionId(login: HttpResult): String =
        login.headers["Set-Cookie"].orEmpty()
            .substringAfter("${ManagementRoutes.COOKIE_NAME}=")
            .substringBefore(';')

    private fun cookieHeaders(id: String): Map<String, String> =
        mapOf("Cookie" to "${ManagementRoutes.COOKIE_NAME}=$id")

    private fun unsafeHeaders(id: String): Map<String, String> = mapOf(
        "Cookie" to "${ManagementRoutes.COOKIE_NAME}=$id",
        "Sec-Fetch-Site" to "same-origin",
    )

    private fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): HttpResult {
        val builder = Request.Builder().url("https://127.0.0.1:$port$path")
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
        return client.newCall(builder.build()).execute().use { response ->
            HttpResult(response.code, response.body?.string().orEmpty(), response.headers)
        }
    }

    private fun awaitReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
        var lastFailure: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                request("GET", "/api/v1/status")
                return
            } catch (failure: Exception) {
                lastFailure = failure
                Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }
        throw AssertionError("the management server never became ready", lastFailure)
    }

    private fun freePort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(ManagementServer.LOOPBACK_HOST)).use { it.localPort }

    private fun trustAllClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private data class HttpResult(val code: Int, val body: String, val headers: Headers)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()

        const val READY_TIMEOUT_MILLIS = 20_000L
        const val RETRY_DELAY_MILLIS = 50L
        const val CONNECT_TIMEOUT_SECONDS = 10L

        const val INDEX_HTML = "<!doctype html><html><head><title>Jarvis</title></head><body>Jarvis management</body></html>"
    }
}
