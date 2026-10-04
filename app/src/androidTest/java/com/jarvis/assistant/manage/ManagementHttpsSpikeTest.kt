package com.jarvis.assistant.manage

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * DEVICE-ONLY diagnostic for the R13 §14 SPIKE. Not part of the CI gate (that
 * job only compiles this source set); it must be run explicitly. Nothing in the
 * app starts [ManagementHttpServer], so this is the only place the embedded
 * HTTPS path is exercised on a real device.
 *
 * It isolates WHERE a failure comes from, in order:
 *
 * - **A — raw TLS sanity.** A plain [SSLServerSocket] (JSSE, no Ktor) loaded
 *   from the [TlsCertFactory] keystore, reached by the test-only trust-all
 *   client. If A fails, the problem is cert/keystore/TLS/API-29 on this device.
 * - **B — Ktor Netty HTTPS.** [ManagementHttpServer] serving `GET /health`
 *   over the same cert. If A passes but B fails, Ktor-Netty-on-Android is the
 *   problem.
 *
 * Each assertion / failure message names the tier it proves, so a device report
 * is unambiguous. The trust-all client lives ONLY in this test source set.
 *
 * Run (see also the class-level command in the R13 report):
 * ```
 * ./gradlew :app:assembleDebugAndroidTest
 * adb install -r app/build/outputs/apk/debug/app-debug.apk
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument -w \
 *   -e class com.jarvis.assistant.manage.ManagementHttpsSpikeTest \
 *   com.jarvis.assistant.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ManagementHttpsSpikeTest {

    private val tls: TlsMaterial by lazy { TlsCertFactory.generate() }

    private val client: OkHttpClient by lazy {
        val trustManager = acceptAllTrustManager()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * A: can this device complete a TLS handshake at all with the generated
     * self-signed cert, using raw JSSE? Deliberately avoids a single Ktor/Netty
     * class, so a failure here can only mean the cert/keystore/TLS stack.
     */
    @Test
    fun a_rawTlsServerSocket_completesHandshakeAndServesTrivialResponse() {
        val serverSocket = rawTlsServerSocket(tls)
        val port = serverSocket.localPort
        val serverFailure = AtomicReference<Throwable?>(null)
        val responder = Thread {
            try {
                serverSocket.accept().use { socket ->
                    socket.inputStream.read(ByteArray(RESPONSE_READ_BUFFER))
                    val payload = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n" +
                        "Content-Length: 2\r\nConnection: close\r\n\r\nok"
                    socket.outputStream.write(payload.toByteArray())
                    socket.outputStream.flush()
                }
            } catch (t: Throwable) {
                serverFailure.set(t)
            }
        }
        responder.isDaemon = true
        responder.start()

        val result = try {
            client.newCall(Request.Builder().url("https://127.0.0.1:$port/health").build())
                .execute()
                .use { it.code to it.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw AssertionError(
                "A FAILED: a plain SSLServerSocket with the generated cert could NOT complete a TLS " +
                    "handshake (server-side failure=$serverFailure). This isolates the problem to " +
                    "cert/keystore/TLS on this device, NOT Ktor. Cause: ${e.message}",
                e,
            )
        } finally {
            runCatching { serverSocket.close() }
            responder.join(THREAD_JOIN_MILLIS)
        }

        println("A_RAW_TLS_RESULT code=${result.first} body=${result.second}")
        assertEquals("A (raw TLS): handshake + request must yield HTTP 200", 200, result.first)
        assertEquals("A (raw TLS): trivial body must round-trip", "ok", result.second)
    }

    /**
     * B: does Ktor-Netty's `sslConnector` bind and terminate TLS on this device?
     * Only meaningful once A proves the cert/keystore path works.
     */
    @Test
    fun b_ktorNettyHttps_servesTheHealthEndpoint() {
        val port = freeLoopbackPort()
        val server = ManagementHttpServer(tls, port)
        try {
            server.start()
            val result = awaitHealth(port)
            println("B_KTOR_NETTY_RESULT code=${result.first} body=${result.second}")
            assertEquals("B (Ktor/Netty): /health must answer 200 over HTTPS", 200, result.first)
            assertEquals(
                "B (Ktor/Netty): /health must return the JSON health document",
                """{"status":"ok"}""",
                result.second,
            )
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            throw AssertionError(
                "B FAILED: Ktor-Netty could not serve HTTPS on this device (start/bind threw). " +
                    "If test A passed, raw TLS works and Ktor/Netty-on-Android is the problem. " +
                    "Cause: ${e.message}",
                e,
            )
        } finally {
            runCatching { server.stop() }
        }
    }

    /** Retries until the Netty connector has finished binding, then returns the response. */
    private fun awaitHealth(port: Int): Pair<Int, String> {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
        var lastError: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                return client.newCall(Request.Builder().url("https://127.0.0.1:$port/health").build())
                    .execute()
                    .use { it.code to it.body?.string().orEmpty() }
            } catch (e: Exception) {
                lastError = e
                Thread.sleep(RETRY_DELAY_MILLIS)
            }
        }
        throw AssertionError(
            "B FAILED: the Ktor-Netty HTTPS listener never answered on 127.0.0.1:$port within " +
                "${READY_TIMEOUT_MILLIS}ms. If test A passed, Ktor/Netty-on-Android is the problem. " +
                "Last error: ${lastError?.message}",
            lastError,
        )
    }

    private fun rawTlsServerSocket(material: TlsMaterial): SSLServerSocket {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(material.keyStore, material.keyPassword) }
            .keyManagers
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, null, SecureRandom())
        return context.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getByName(LOOPBACK)) as SSLServerSocket
    }

    private fun freeLoopbackPort(): Int =
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }

    private fun acceptAllTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val READY_TIMEOUT_MILLIS = 15_000L
        const val RETRY_DELAY_MILLIS = 100L
        const val THREAD_JOIN_MILLIS = 2_000L
        const val RESPONSE_READ_BUFFER = 512
    }
}
