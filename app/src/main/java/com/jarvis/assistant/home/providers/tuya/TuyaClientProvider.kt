package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.net.okhttp.OkHttpHomeTransport
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The live connection details of one Tuya Cloud Project. [accessId] is the
 * project's Access ID / `client_id`; [secret] is the Access Secret. Both are
 * null/blank until the user enters them.
 */
data class TuyaConnection(
    val region: TuyaRegion,
    val uid: String?,
    val accessId: String?,
    val secret: String?,
)

/**
 * Process-scoped owner of the Tuya OpenAPI client, keyed by config id.
 *
 * WHY PROCESS-SCOPED: `AppGraph` is rebuilt on every service start / watchdog
 * revive WITHIN THE SAME PROCESS (the [com.jarvis.assistant.home.providers.ha.HomeAssistantClientProvider]
 * rationale). The client is cached per Tuya config id so a user with a single
 * Tuya project reuses one token cache across graph rebuilds, while a second
 * project gets its own.
 *
 * The [connection] reader is captured on the FIRST [get] for a given id: it
 * reads the CURRENT prefs/secret at call time, but because the client itself is
 * memoized, re-pointing the region or rotating the Access Secret takes a service
 * restart to rebuild cleanly (the token cache would otherwise be stale).
 *
 * Deliberately Android-free (no `Context`): the reader is supplied by the caller.
 * Nothing connects here — the client authenticates lazily on first use.
 */
object TuyaClientProvider {

    @Volatile
    private var clients: Map<String, TuyaOpenApiClient> = emptyMap()

    /** The client for [configId]; [connection] is used on the first call for that id. */
    fun get(configId: String, connection: () -> TuyaConnection): TuyaOpenApiClient {
        clients[configId]?.let { return it }
        return synchronized(this) {
            clients[configId] ?: build(connection).also { clients = clients + (configId to it) }
        }
    }

    private fun build(connection: () -> TuyaConnection): TuyaOpenApiClient {
        val transport = OkHttpHomeTransport(defaultClient())
        return TuyaOpenApiClient(
            transport = transport,
            baseUrl = { connection().region.baseUrl },
            accessId = { connection().accessId },
            secret = { connection().secret },
        )
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Test seam: drop the memoized clients so a test can re-observe first-call behavior. */
    fun clearForTests() {
        clients = emptyMap()
    }

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 30L
}
