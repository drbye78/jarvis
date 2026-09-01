package com.jarvis.assistant.media

/**
 * Pure-Kotlin music-app resolver: decides which installed player a
 * "play music" command targets. The Android side only supplies the
 * installed (package, label) list — everything below is unit-tested.
 *
 * Resolution order:
 *  1. LLM hint names a brand ("яндекс", "вк", "звук") → that brand's
 *     installed package. An explicit per-request hint always wins —
 *     "включи в Звуке" targets Zvuk even when Яндекс is the default.
 *  2. LLM hint matches an installed app's label ("вк" → "VK Музыка").
 *  3. The user's preferred player from Settings ("Музыка" card) when
 *     installed ("звук" preferred → com.zvooq.openplay).
 *  4. No preference ("auto") → first KNOWN player in priority order
 *     (Яндекс Музыка first — this project's default target), else any
 *     launchable app whose label looks like a music player.
 */
class MusicAppCatalog(
    /** Installed launchable apps as (packageName, label) pairs. */
    private val installed: () -> List<Pair<String, String>>,
    /**
     * Package name of the user's preferred default player, or null for
     * "auto" priority. Production reads it from [com.jarvis.assistant.util.AppPrefs]
     * via the composition root; tests pass a constant.
     */
    private val preferredPackage: () -> String? = { null },
) : MusicAppResolver {

    override fun resolve(appHint: String?): MediaAppInfo? {
        val apps = installed()
        val hint = appHint?.trim()?.lowercase()

        fun info(pkg: String): MediaAppInfo? =
            apps.firstOrNull { it.first == pkg }?.let { MediaAppInfo(pkg, it.second) }

        val known = KNOWN_PLAYERS.mapNotNull { (pkg, brand) -> info(pkg)?.let { pkg to brand } }

        if (hint != null) {
            // Specific brand tokens first: a hint that names a brand must
            // not be captured by ANOTHER player's generic "музык" token —
            // the playMusic schema itself suggests 'VK Музыка' as an app
            // value, and "vk музыка" contains "музык", so a single
            // first-match pass over KNOWN_PLAYERS order (Yandex first)
            // routed VK requests to Yandex. Two passes: non-generic tokens
            // only, then the generic token as a bare-«музыка» fallback.
            val generic = GENERIC_TOKEN
            known.firstOrNull { (_, brand) ->
                brand.split('|').filter { it != generic }.any { hint.contains(it) }
            }?.let { return info(it.first) }
            known.firstOrNull { (_, brand) ->
                brand.split('|').any { hint.contains(it) }
            }?.let { return info(it.first) }
            if (hint.length >= 2) {
                apps.firstOrNull { (_, label) -> label.lowercase().contains(hint) }
                    ?.let { return MediaAppInfo(it.first, it.second) }
            }
        }

        // 3) The user's preferred default player (Settings → «Музыка»).
        // Only applied when actually installed — a preference for an
        // uninstalled player degrades to the auto priority below.
        preferredPackage()?.let { preferred -> info(preferred)?.let { return it } }

        // 4) Auto priority: KNOWN_PLAYERS order (Яндекс Музыка first).
        return known.firstOrNull()?.let { info(it.first) }
            ?: apps.firstOrNull { (_, label) ->
                val l = label.lowercase()
                MUSIC_LABEL_KEYWORDS.any { l.contains(it) }
            }?.let { MediaAppInfo(it.first, it.second) }
    }

    private companion object {
        /** pkg → brand tokens matched against a lowercase hint. */
        val KNOWN_PLAYERS = listOf(
            "ru.yandex.music" to "яндекс|yandex|музык",
            "com.yandex.music" to "яндекс|yandex|музык",
            "com.zvooq.openplay" to "звук|zvuk|сберзвук",
            "com.vk.music" to "вк|vk",
        )

        /**
         * The generic token: "музык" appears in Yandex's brand list so a
         * bare «музыка» hint still lands on the default player, but it must
         * never override a specific brand marker in the same hint.
         */
        const val GENERIC_TOKEN = "музык"

        val MUSIC_LABEL_KEYWORDS = listOf("музык", "music", "звук", "zvuk")
    }
}
