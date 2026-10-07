package com.jarvis.assistant.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * DEVICE-ONLY end-to-end check of the SberTrust TLS context.
 *
 * The JVM unit tests (see [SberTrustTest]) simulate Android's per-domain
 * `RootTrustManager` behavior with fakes; this test proves the REAL platform
 * manager accepts an ordinary public leaf through
 * [SberTrust.sslContext] + [SberTrust.compositeTrustManager]. On the API-29
 * wall device the app declares a `<domain-config>` (the loopback MCP
 * exception), which makes the platform 2-arg overload throw — the exact
 * condition that broke every non-Sber host before the 3-arg forwarding fix.
 *
 * Success is "the TLS handshake completed": any HTTP status is fine
 * (401/403 included), only an [SSLHandshakeException] is a failure. When the
 * device has no route (offline CI), the test skips instead of failing.
 * No credentials are used or required.
 */
@RunWith(AndroidJUnit4::class)
class SberTrustDeviceTest {

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .sslSocketFactory(
                SberTrust.sslContext().socketFactory,
                SberTrust.compositeTrustManager(),
            )
            .build()
    }

    private fun assertTlsHandshakeCompletes(url: String) {
        val request = Request.Builder().url(url).get().build()
        try {
            client.newCall(request).execute().use { response ->
                // Reaching this point at all means the handshake completed;
                // an HTTP error status is expected and NOT a trust failure.
                response.code
            }
        } catch (e: SSLHandshakeException) {
            fail("TLS handshake through SberTrust.sslContext() failed for $url: ${e.message}")
        } catch (e: IOException) {
            // DNS/route/timeout: the context was never exercised. Skip rather
            // than report a false regression on an offline device.
            assumeNoException("network unavailable for $url", e)
        }
    }

    @Test
    fun tlsHandshakeCompletesForPublicHost() {
        assertTlsHandshakeCompletes("https://example.com/")
    }

    @Test
    fun tlsHandshakeCompletesForYandexAiStudio() {
        // The AI Studio LLM is one of the non-Sber hosts that shares the
        // hardened OkHttp client; a 401/403 (no key) still proves the
        // handshake succeeded.
        assertTlsHandshakeCompletes("https://ai.api.cloud.yandex.net/v1/models")
    }
}
