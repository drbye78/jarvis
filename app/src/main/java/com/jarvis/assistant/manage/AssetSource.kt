package com.jarvis.assistant.manage

/**
 * Seam over the static single-page-app bytes (R13 §14.5).
 *
 * Deliberately tiny so the transport stays Android-free and JVM-testable: the
 * production wiring (a later lane) maps paths onto `AssetManager` under
 * `assets/web/`, while tests supply a canned map. [path] is the request path
 * WITH a leading slash (e.g. `/`, `/index.html`, `/app.js`); an implementation
 * returns the bytes or null when nothing is stored at that path.
 *
 * A `fun interface` so a test can pass a lambda without a named class.
 */
fun interface AssetSource {
    /** Bytes for [path], or null when the asset is absent. */
    fun read(path: String): ByteArray?
}
