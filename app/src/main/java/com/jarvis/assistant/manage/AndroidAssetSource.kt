package com.jarvis.assistant.manage

import android.content.Context
import java.io.IOException

/**
 * Production [AssetSource] for the R13 §14.5 static SPA: reads the packaged
 * `assets/web/` bytes through the [android.content.res.AssetManager].
 *
 * The request path carries a leading slash (`/`, `/index.html`); the asset
 * manager addresses entries WITHOUT it, so it is stripped. A missing entry
 * (including a directory) is reported as `null`, which the route layer turns
 * into the `index.html` SPA fallback — a missing asset is a normal 404/fallback,
 * never an error.
 */
class AndroidAssetSource(context: Context) : AssetSource {

    private val assets = context.applicationContext.assets

    @Suppress("SwallowedException") // a missing asset is a normal SPA fallback, not an error
    override fun read(path: String): ByteArray? = try {
        assets.open(path.removePrefix("/")).use { it.readBytes() }
    } catch (_: IOException) {
        null
    }
}
