package com.jarvis.assistant.media

/**
 * Pure resolver from a SPOKEN player name to the persisted
 * [com.jarvis.assistant.util.AppPrefs.preferredMusicPlayer] value — the same
 * pref the Settings «Музыка» radio writes (see
 * [com.jarvis.assistant.ui.SettingsMapping.playerPrefFor]).
 *
 * Android-free and JVM-tested: the voice lane passes the LLM's `app` slot
 * here, so no Context/GPS/package APIs are needed to decide what to store.
 * An unknown name returns null and the caller answers honestly instead of
 * silently resetting the preference to "auto".
 *
 * The brand tokens deliberately mirror [MusicAppCatalog.KNOWN_PLAYERS]
 * (`яндекс|yandex`, `звук|zvuk|сберзвук`, `вк|vk`); keep the two lists in
 * sync so a spoken brand resolves to the same package the catalog targets.
 */
object MusicPlayerChoice {

    /** Automatic choice (Яндекс Музыка first) — the Settings AUTO value. */
    const val AUTO: String = "auto"

    /** Canonical Yandex Music package (the Settings radio's write value). */
    const val YANDEX_MUSIC: String = "ru.yandex.music"

    /** Zvuk (СберЗвук) package. */
    const val ZVUK: String = "com.zvooq.openplay"

    /** VK Music package. */
    const val VK_MUSIC: String = "com.uma.musicvk"

    /**
     * Whole normalized phrases that mean "automatic choice". Matched EXACTLY
     * (not as substrings): «плеер по умолчанию — Яндекс Музыка» must select
     * Yandex, not reset to auto, when the model passes the whole phrase.
     */
    private val AUTO_PHRASES = setOf(
        "авто",
        "автоматически",
        "по умолчанию",
        "сброс",
        "сбрось",
        "верни авто",
        "auto",
        "default",
    )

    private val YANDEX_TOKENS = listOf("яндекс", "yandex")
    private val ZVUK_TOKENS = listOf("звук", "zvuk", "сберзвук")
    private val VK_TOKENS = listOf("вк", "vk")

    private val WHITESPACE = Regex("\\s+")

    /**
     * The pref value for [spoken], or null when the name is not a known
     * player (the caller then returns an honest "unknown player" error).
     */
    fun prefValue(spoken: String): String? {
        val normalized = spoken.trim().lowercase().replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return null
        if (normalized in AUTO_PHRASES) return AUTO
        return when {
            YANDEX_TOKENS.any { normalized.contains(it) } -> YANDEX_MUSIC
            ZVUK_TOKENS.any { normalized.contains(it) } -> ZVUK
            VK_TOKENS.any { normalized.contains(it) } -> VK_MUSIC
            else -> null
        }
    }
}
