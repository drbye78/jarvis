package com.jarvis.assistant.tools

/** An installed launchable app: package id + the user-visible label. */
data class InstalledApp(val packageName: String, val label: String)

/**
 * Pure, Android-free resolver mapping a spoken app name to an installed
 * package. `openApp` used to match only `label.contains(query)`, so a
 * Cyrillic spoken form or an ASR transliteration never reached an app whose
 * label is Latin: the target tablet has «VK Музыка» installed as
 * `com.uma.musicvk` with the Latin label "VK Музыка", and «включи приложение
 * викей музыка» failed with «Приложение 'ВК Музыка' не найдено».
 *
 * Resolution order:
 *  1. exact normalized label;
 *  2. known alias / transliteration table (spoken RU form → package);
 *  3. the legacy token match: `normalizedLabel.contains(normalizedQuery)`.
 *
 * The [installed] list is supplied by the Android side, so every branch below
 * is unit-testable on the JVM without a PackageManager.
 */
object AppAliases {

    fun resolve(query: String, installed: List<InstalledApp>): InstalledApp? {
        val normalized = normalize(query)
        if (normalized.isEmpty()) return null
        return installed.firstOrNull { normalize(it.label) == normalized }
            ?: byAlias(normalized, installed)
            ?: byAlias(transliterate(normalized), installed)
            ?: installed.firstOrNull { normalize(it.label).contains(normalized) }
    }

    private fun byAlias(key: String, installed: List<InstalledApp>): InstalledApp? {
        val pkg = ALIASES[key] ?: return null
        return installed.firstOrNull { it.packageName == pkg }
    }

    /** Trim, lowercase, strip punctuation and collapse whitespace. */
    private fun normalize(raw: String): String = raw
        .lowercase()
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

    /**
     * Token-level Cyrillic→Latin transliteration. Whole tokens are mapped
     * (ASR renders «ВК» as «викей», which is not a letter-by-letter pair);
     * unmapped tokens pass through unchanged so «вк музыка» → "vk музыка".
     */
    private fun transliterate(normalized: String): String =
        normalized.split(' ').joinToString(" ") { TRANSLIT[it] ?: it }

    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE = Regex("\\s+")

    /** Spoken-token → Latin token; applied before the alias lookup. */
    private val TRANSLIT: Map<String, String> = mapOf(
        "вк" to "vk",
        "викей" to "vk",
        "вконтакте" to "vk",
        "яндекс" to "yandex",
        "звук" to "zvuk",
    )

    /**
     * Known spoken forms → installed package. Keys are already normalized
     * and, where the spoken form is Cyrillic, may be reached through
     * [TRANSLIT] instead of an explicit key (e.g. «викей музыка» →
     * "vk музыка"). Only packages we can justify from the device catalog /
     * [com.jarvis.assistant.media.MusicAppCatalog] are listed.
     */
    private val ALIASES: Map<String, String> = mapOf(
        // VK Музыка (com.uma.musicvk) — label is Latin "VK Музыка".
        // «вк музыка», «викей музыка» and «вконтакте музыка» all transliterate
        // to "vk музыка" via TRANSLIT; the explicit keys cover the Latin and
        // unspaced Cyrillic forms.
        "vk музыка" to "com.uma.musicvk",
        "vk music" to "com.uma.musicvk",
        "вкмузыка" to "com.uma.musicvk",
        // Яндекс Музыка (ru.yandex.music).
        "yandex музыка" to "ru.yandex.music",
        "yandex music" to "ru.yandex.music",
        "яндексмузыка" to "ru.yandex.music",
        // Звук (com.zvooq.openplay).
        "звук" to "com.zvooq.openplay",
        "zvuk" to "com.zvooq.openplay",
        "zvuk музыка" to "com.zvooq.openplay",
    )
}
