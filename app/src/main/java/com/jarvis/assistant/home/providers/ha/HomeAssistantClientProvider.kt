package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.net.okhttp.OkHttpHomeTransport
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The live connection details of the configured Home Assistant instance.
 * [token] is null/blank before the user has entered a long-lived access token.
 */
data class HaConnection(
    val baseUrl: String,
    val token: String?,
)

/**
 * Process-scoped owner of the Home Assistant transport + backend.
 *
 * WHY PROCESS-SCOPED: `AppGraph` is rebuilt on every service start / restart /
 * watchdog revive WITHIN THE SAME PROCESS, so a graph-owned backend would
 * churn its websocket on each rebuild. This mirrors
 * [com.jarvis.assistant.geo.mapkit.MapKitInitializerProvider]: one instance per
 * process, memoized lazily, never rebuilt per graph.
 *
 * The [connection] reader is captured on the FIRST [get] call. `AppGraph` should
 * pass a lambda that reads the CURRENT `AppPrefs.homeProviders` / `homeSecret`
 * values (so a token edited later is still visible), but because the provider
 * itself does not rebuild, RE-POINTING Home Assistant at a different host (or
 * rotating the token) takes a service restart to re-establish the connection.
 *
 * Deliberately Android-free (no `Context`): the reader is supplied by the caller,
 * so `home/` stays free of Android/`tools`/`settings` imports. The production
 * caller builds the reader from `AppPrefs` + `SecretVault` and calls
 * `HomeAssistantClientProvider.get { readHaConnection() }`.
 *
 * Nothing is started here — this is only the process-stable holder; the backend
 * connects lazily when a tool actually discovers/reads/applies.
 */
object HomeAssistantClientProvider {

    @Volatile
    private var backend: HomeAssistantBackend? = null

    /** The one backend for this process; [connection] is used on the first call only. */
    fun get(connection: () -> HaConnection): HomeAssistantBackend {
        backend?.let { return it }
        return synchronized(this) {
            backend ?: build(connection).also { backend = it }
        }
    }

    private fun build(connection: () -> HaConnection): HomeAssistantBackend {
        val transport = OkHttpHomeTransport(defaultClient())
        return HomeAssistantBackend(
            transport = transport,
            webSocket = transport,
            baseUrl = { connection().baseUrl },
            token = { connection().token },
        )
    }

    private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** Test seam: drop the memoized backend so a test can re-observe first-call behavior. */
    fun clearForTests() {
        backend = null
    }

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L
    private const val CALL_TIMEOUT_SECONDS = 30L
}
