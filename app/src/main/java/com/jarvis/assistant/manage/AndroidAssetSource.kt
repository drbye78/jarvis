package com.jarvis.assistant.manage

import android.content.Context
import java.io.IOException

/**
 * Production [AssetSource] for the R13 §14.5 static SPA: reads the packaged
 * `assets/web/` bytes through the [android.content.res.AssetManager].
 *
 * The request path carries a leading slash (`/`, `/index.html`); the asset
 * manager addresses entries WITHOUT it, so it is stripped. The SPA lives under
 * `assets/web/`, so the stripped request path is prefixed with [WEB_ROOT] — the
 * routes deliberately speak browser-absolute paths (`/app.js`), and the asset
 * manager has no notion of the web root, so this is the ONE place the two are
 * bridged. A missing entry (including a directory) is reported as `null`, which
 * the route layer turns into the `index.html` SPA fallback — a missing asset is
 * a normal 404/fallback, never an error.
 */
class AndroidAssetSource(context: Context) : AssetSource {

    private val assets = context.applicationContext.assets

    @Suppress("SwallowedException") // a missing asset is a normal SPA fallback, not an error
    override fun read(path: String): ByteArray? = try {
        assets.open("$WEB_ROOT/${path.removePrefix("/")}").use { it.readBytes() }
    } catch (_: IOException) {
        null
    }

    companion object {
        /** Directory inside `assets/` that holds the static management console. */
        const val WEB_ROOT = "web"
    }
}
