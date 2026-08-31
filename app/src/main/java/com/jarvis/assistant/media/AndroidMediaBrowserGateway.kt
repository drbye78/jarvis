package com.jarvis.assistant.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * Tier 3 adapter: binds other apps' MediaBrowserService via
 * [MediaBrowserCompat]. Every async platform callback is bridged into a
 * suspending call with a hard timeout, so a hung service can never wedge the
 * cascade — the strategy budget expires, the browser is disconnected, and
 * the next strategy runs.
 *
 * Behavior matrix (ground truth per player, revealed by the MusicDiag dump):
 *  - onGetRoot returns null          → onConnectionFailed → connect() = null
 *  - onGetRoot returns EMPTY root    → onConnected, token available (no
 *    browsing, but S2 cold start still works)
 *  - browsable root                  → children() returns the library tree
 *  - onSearch implemented            → search() returns scored results (S0)
 *
 * Permission model: binding needs NO permission and NO notification-listener
 * access; the session token controller is the Assistant-grade headless path.
 */
class AndroidMediaBrowserGateway(private val context: Context) : MediaBrowserGateway {

    private val appContext = context.applicationContext

    override fun discover(): List<BrowserServiceInfo> = runCatching {
        val pm = appContext.packageManager
        pm.queryIntentServices(browserIntent(), 0)
            .map { info ->
                BrowserServiceInfo(
                    packageName = info.serviceInfo.packageName,
                    label = info.serviceInfo.loadLabel(pm).toString(),
                )
            }
    }.getOrDefault(emptyList())

    override suspend fun connect(packageName: String, timeoutMs: Long): BrowserSession? {
        val service = runCatching {
            appContext.packageManager
                .queryIntentServices(browserIntent().setPackage(packageName), 0)
                .firstOrNull()?.serviceInfo
        }.getOrNull() ?: return null
        val component = ComponentName(service.packageName, service.name)

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var browser: MediaBrowserCompat? = null
                val callback = object : MediaBrowserCompat.ConnectionCallback() {
                    override fun onConnected() {
                        // Refused-root services never reach here; empty-root
                        // services DO (token, no browse) — that is S2's lane.
                        val b = browser
                        if (cont.isActive) {
                            if (b != null) {
                                cont.resume(ConnectedSession(this@AndroidMediaBrowserGateway.appContext, b))
                            } else {
                                cont.resume(null)
                            }
                        }
                    }

                    override fun onConnectionFailed() {
                        Timber.i("BrowserDiag: %s refused the connection (null root)", packageName)
                        if (cont.isActive) cont.resume(null)
                    }

                    override fun onConnectionSuspended() {
                        // Service died mid-use; the session's next op fails
                        // best-effort. Nothing to resume — connect() is done.
                    }
                }
                val b = runCatching {
                    MediaBrowserCompat(appContext, component, callback, null)
                }.getOrNull()
                if (b == null) {
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                browser = b
                cont.invokeOnCancellation { runCatching { b.disconnect() } }
                runCatching { b.connect() }.onFailure {
                    Timber.w(it, "BrowserDiag: connect() threw for %s", packageName)
                    runCatching { b.disconnect() }
                    if (cont.isActive) cont.resume(null)
                }
            }
        }?.also { session ->
            Timber.d("BrowserDiag: connected to %s (root=«%s»)", packageName, session.root())
        }
    }

    private fun browserIntent(): Intent =
        Intent("android.media.browse.MediaBrowserService")

    /**
     * One live bind. Holds the browser and lazily builds a single compat
     * controller from the session token; [disconnect] is idempotent.
     */
    private class ConnectedSession(
        private val appContext: Context,
        private val browser: MediaBrowserCompat,
    ) : BrowserSession {

        override val packageName: String
            get() = browser.serviceComponent?.packageName ?: ""

        private var controllerRef: MediaControllerCompat? = null
        private var disconnected = false

        override fun root(): String = runCatching { browser.root }.getOrDefault("")

        override suspend fun search(
            query: String,
            timeoutMs: Long,
        ): List<BrowserMediaItem>? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    browser.search(query, null, object : MediaBrowserCompat.SearchCallback() {
                        override fun onSearchResult(
                            query: String,
                            extras: Bundle?,
                            items: MutableList<MediaBrowserCompat.MediaItem>,
                        ) {
                            if (cont.isActive) {
                                cont.resume(items.map { it.toItem() })
                            }
                        }

                        override fun onError(query: String, extras: Bundle?) {
                            // Most common cause: the service never implemented
                            // onSearch — an expected miss, not an error.
                            Timber.i("BrowserDiag: onSearch unsupported or failed for «%s»", query)
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        override suspend fun children(
            parentId: String,
            timeoutMs: Long,
            max: Int,
        ): List<BrowserMediaItem>? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val callback = object : MediaBrowserCompat.SubscriptionCallback() {
                    override fun onChildrenLoaded(
                        parentId: String,
                        children: MutableList<MediaBrowserCompat.MediaItem>,
                    ) {
                        runCatching { browser.unsubscribe(parentId, this) }
                        if (cont.isActive) {
                            cont.resume(children.take(max).map { it.toItem() })
                        }
                    }

                    override fun onError(id: String) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
                runCatching {
                    browser.subscribe(parentId, callback)
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        override fun controller(): MediaControllerHandle? =
            controllerCompat()?.let { AndroidControllerHandle(it) }

        override fun playFromMediaId(mediaId: String): Boolean {
            val c = controllerCompat() ?: return false
            return runCatching {
                c.transportControls.playFromMediaId(mediaId, null)
                true
            }.getOrDefault(false)
        }

        override fun disconnect() {
            if (disconnected) return
            disconnected = true
            runCatching { browser.disconnect() }
        }

        private fun controllerCompat(): MediaControllerCompat? {
            controllerRef?.let { return it }
            if (disconnected) return null
            val token = runCatching { browser.sessionToken }.getOrNull() ?: return null
            return runCatching { MediaControllerCompat(appContext, token) }
                .getOrNull()
                ?.also { controllerRef = it }
        }
    }
}

private fun MediaBrowserCompat.MediaItem.toItem(): BrowserMediaItem = BrowserMediaItem(
    mediaId = mediaId ?: "",
    title = description?.title?.toString().orEmpty(),
    artist = description?.subtitle?.toString(),
    playable = (flags and MediaBrowserCompat.MediaItem.FLAG_PLAYABLE) != 0,
    browsable = (flags and MediaBrowserCompat.MediaItem.FLAG_BROWSABLE) != 0,
)
