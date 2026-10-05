package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.HomeError
import com.jarvis.assistant.home.HomeResult
import com.jarvis.assistant.home.net.HomeHttpTransport
import com.jarvis.assistant.home.net.HttpResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Token lifecycle, signed headers and the one-shot re-auth retry. */
class TuyaOpenApiClientTest {

    private class FakeTransport(
        private val responder: (
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ) -> HttpResponse,
    ) : HomeHttpTransport {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()

        override suspend fun get(url: String, headers: Map<String, String>): HttpResponse {
            requests += Triple("GET", url, headers)
            return responder("GET", url, headers, null)
        }

        override suspend fun post(url: String, headers: Map<String, String>, body: String): HttpResponse {
            requests += Triple("POST", url, headers)
            return responder("POST", url, headers, body)
        }
    }

    private fun client(
        transport: FakeTransport,
        clock: () -> Long = { 1_000L },
    ) = TuyaOpenApiClient(
        transport = transport,
        baseUrl = { TuyaRegion.CENTRAL_EUROPE.baseUrl },
        accessId = { "id" },
        secret = { "secret" },
        nowMs = clock,
        nonce = { "nonce" },
        json = Json { ignoreUnknownKeys = true },
    )

    @Test
    fun `token is fetched then cached`() = kotlinx.coroutines.test.runTest {
        var calls = 0
        val transport = FakeTransport { _, url, _, _ ->
            if (url.contains("/token")) {
                calls++
                HttpResponse(200, """{"success":true,"result":{"access_token":"tok","expire_time":7200}}""")
            } else {
                HttpResponse(200, """{"success":true,"result":{}}""")
            }
        }
        val api = client(transport)
        assertEquals("tok", (api.token() as HomeResult.Ok).value)
        assertEquals("tok", (api.token() as HomeResult.Ok).value)
        assertEquals("the second call is served from cache", 1, calls)
    }

    @Test
    fun `a token request signs without an access token and sets headers`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { _, _, _, _ ->
            HttpResponse(200, """{"success":true,"result":{"access_token":"tok","expire_time":7200}}""")
        }
        client(transport).token()
        val headers = transport.requests.single().third
        assertEquals("id", headers["client_id"])
        assertEquals(TuyaSigning.SIGN_METHOD, headers["sign_method"])
        assertEquals("nonce", headers["nonce"])
        assertEquals("1000", headers["t"])
        assertTrue("a token request must NOT send access_token", "access_token" !in headers)
        assertTrue("signature is 64 uppercase hex", headers.getValue("sign").matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun `a business request sends the access token header`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { _, url, _, _ ->
            if (url.contains("/token")) {
                HttpResponse(200, """{"success":true,"result":{"access_token":"tok","expire_time":7200}}""")
            } else {
                HttpResponse(200, """{"success":true,"result":[]}""")
            }
        }
        client(transport).get("/v1.0/users/u/devices")
        val business = transport.requests.last().third
        assertEquals("tok", business["access_token"])
    }

    @Test
    fun `an expired token triggers exactly one re-auth and retry`() = kotlinx.coroutines.test.runTest {
        var tokenCalls = 0
        var businessCalls = 0
        val transport = FakeTransport { _, url, headers, _ ->
            if (url.contains("/token")) {
                tokenCalls++
                val token = if (tokenCalls == 1) "old" else "new"
                HttpResponse(200, """{"success":true,"result":{"access_token":"$token","expire_time":7200}}""")
            } else {
                businessCalls++
                if (headers["access_token"] == "old") {
                    HttpResponse(200, """{"success":false,"code":1010,"msg":"token invalid"}""")
                } else {
                    HttpResponse(200, """{"success":true,"result":[1]}""")
                }
            }
        }
        val result = client(transport).get("/v1.0/users/u/devices")
        assertTrue(result is HomeResult.Ok)
        assertEquals("re-authenticated once", 2, tokenCalls)
        assertEquals("retried exactly once", 2, businessCalls)
    }

    @Test
    fun `a second expiry is not retried again`() = kotlinx.coroutines.test.runTest {
        var businessCalls = 0
        val transport = FakeTransport { _, url, _, _ ->
            if (url.contains("/token")) {
                HttpResponse(200, """{"success":true,"result":{"access_token":"tok","expire_time":7200}}""")
            } else {
                businessCalls++
                HttpResponse(200, """{"success":false,"code":1010,"msg":"token invalid"}""")
            }
        }
        val result = client(transport).get("/v1.0/users/u/devices")
        assertEquals(HomeError.AUTH, (result as HomeResult.Err).error)
        assertEquals("one attempt + one retry, then stop", 2, businessCalls)
    }

    @Test
    fun `missing credentials report not configured without a request`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { _, _, _, _ -> HttpResponse(200, "{}") }
        val api = TuyaOpenApiClient(
            transport = transport,
            baseUrl = { TuyaRegion.CENTRAL_EUROPE.baseUrl },
            accessId = { "" },
            secret = { "" },
        )
        assertEquals(HomeError.NOT_CONFIGURED, (api.token() as HomeResult.Err).error)
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun `a 401 http status is an auth error`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { _, url, _, _ ->
            if (url.contains("/token")) {
                HttpResponse(200, """{"success":true,"result":{"access_token":"tok"}}""")
            } else {
                HttpResponse(401, "{}")
            }
        }
        assertEquals(HomeError.AUTH, (client(transport).get("/x") as HomeResult.Err).error)
    }

    @Test
    fun `a transport failure is unreachable`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { _, _, _, _ ->
            throw com.jarvis.assistant.home.net.HomeTransportException("boom")
        }
        assertEquals(HomeError.UNREACHABLE, (client(transport).get("/x") as HomeResult.Err).error)
    }

    @Test
    fun `query parameters are signed in sorted order`() = kotlinx.coroutines.test.runTest {
        val transport = FakeTransport { _, url, _, _ ->
            if (url.contains("/token")) {
                HttpResponse(200, """{"success":true,"result":{"access_token":"tok"}}""")
            } else {
                HttpResponse(200, """{"success":true,"result":{}}""")
            }
        }
        client(transport).get("/v1.0/x", listOf("b" to "2", "a" to "1"))
        assertTrue(transport.requests.last().second.endsWith("/v1.0/x?a=1&b=2"))
    }
}
