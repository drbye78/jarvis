package com.jarvis.assistant.media

/**
 * Pure-Kotlin music-app resolver: decides which installed player a
 * "play music" command targets. The Android side only supplies the
 * installed (package, label) list — everything below is unit-tested.
 *
 * Resolution order:
 *  1. LLM hint names a brand ("яндекс", "вк", "звук") → that brand's
 *     installed package.
 *  2. LLM hint matches an installed app's label ("вк" → "VK Музыка").
 *  3. No hint (or unrecognized) → first KNOWN player in priority order
 *     (Яндекс Музыка first — this project's target player), else any
 *     launchable app whose label looks like a music player.
 */
class MusicAppCatalog(
    /** Installed launchable apps as (packageName, label) pairs. */
    private val installed: () -> List<Pair<String, String>>,
) : MusicAppResolver {

    override fun resolve(appHint: String?): MediaAppInfo? {
        val apps = installed()
        val hint = appHint?.trim()?.lowercase()

        fun info(pkg: String): MediaAppInfo? =
            apps.firstOrNull { it.first == pkg }?.let { MediaAppInfo(pkg, it.second) }

        val known = KNOWN_PLAYERS.mapNotNull { (pkg, brand) -> info(pkg)?.let { pkg to brand } }

        if (hint != null) {
            known.firstOrNull { (_, brand) -> brand.split('|').any { hint.contains(it) } }
                ?.let { return info(it.first) }
            if (hint.length >= 2) {
                apps.firstOrNull { (_, label) -> label.lowercase().contains(hint) }
                    ?.let { return MediaAppInfo(it.first, it.second) }
            }
        }

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
            "com.zvooq.openplay" to "звук|zvuk",
            "com.vk.music" to "вк|vk",
        )

        val MUSIC_LABEL_KEYWORDS = listOf("музык", "music", "звук", "zvuk")
    }
}
