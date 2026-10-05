package com.jarvis.assistant.home

/** Resolution intent. CONTROL is a device actuation; READ is a status question. */
enum class ResolveMode { CONTROL, READ }

/** The result of resolving a spoken query against the in-memory catalog. */
sealed interface HomeResolution {
    /** Exactly one device; only this is executable. */
    data class Unique(val device: HomeDevice) : HomeResolution

    /** More than one plausible device; a CONTROL caller MUST fail closed. */
    data class Ambiguous(val candidates: List<HomeDevice>) : HomeResolution

    /** Nothing matched. */
    data object NotFound : HomeResolution
}

/**
 * Pure, deterministic, fail-closed spoken-name resolver.
 *
 * Order: explicit alias → exact normalized name → room+kind composition →
 * token-substring (READ only) → [HomeResolution.NotFound].
 *
 * The normalizer/transliteration is OWNED here (a small RU-focused copy) rather
 * than imported from `tools/AppAliases`, so this package has no dependency on
 * the tool lane — the dependency runs the other way.
 *
 * CONTROL fails closed: only [HomeResolution.Unique] may be executed by a
 * caller. [HomeResolution.Ambiguous] is surfaced so the caller can ask the user
 * to disambiguate, never guessed at. The loose token-substring step is disabled
 * in CONTROL because a single fuzzy match is not evidence of intent; READ may
 * use it to surface candidates for a status answer.
 */
object HomeResolver {

    fun resolve(
        query: String,
        devices: List<HomeDevice>,
        aliases: Map<String, HomeDeviceKey> = emptyMap(),
        rooms: Set<String> = emptySet(),
        mode: ResolveMode = ResolveMode.CONTROL,
    ): HomeResolution {
        val normalized = HomeNormalizer.normalize(query)
        if (normalized.isEmpty()) return HomeResolution.NotFound
        val roomVocab = rooms + devices.mapNotNull { it.room }
        return resolveAlias(normalized, devices, aliases)
            ?: resolveExact(normalized, devices)
            ?: resolveComposed(normalized, devices, roomVocab)
            ?: resolveSubstring(normalized, devices, mode)
            ?: HomeResolution.NotFound
    }

    private fun resolveAlias(
        normalized: String,
        devices: List<HomeDevice>,
        aliases: Map<String, HomeDeviceKey>,
    ): HomeResolution? {
        val target = aliases[normalized] ?: aliases[HomeNormalizer.transliterate(normalized)] ?: return null
        val device = devices.firstOrNull { it.key == target } ?: return null
        return HomeResolution.Unique(device)
    }

    private fun resolveExact(normalized: String, devices: List<HomeDevice>): HomeResolution? =
        classifyMatches(devices.filter { HomeNormalizer.normalize(it.name) == normalized })

    private fun resolveComposed(
        normalized: String,
        devices: List<HomeDevice>,
        rooms: Collection<String>,
    ): HomeResolution? {
        val tokens = HomeNormalizer.tokens(normalized)
        val kind = kindFromTokens(tokens)
        val room = roomFromTokens(tokens, rooms)
        if (kind == null && room == null) return null
        val candidates = devices.filter { device ->
            val kindOk = kind == null || device.kind == kind
            val roomOk = room == null || roomMatches(device.room, room)
            kindOk && roomOk
        }
        return classifyMatches(candidates)
    }

    private fun resolveSubstring(normalized: String, devices: List<HomeDevice>, mode: ResolveMode): HomeResolution? {
        if (mode != ResolveMode.READ) return null
        val needles = normalized.split(' ').filter { it.length >= MIN_SUBSTRING_LENGTH }
        if (needles.isEmpty()) return null
        val matches = devices.filter { device ->
            val name = HomeNormalizer.normalize(device.name)
            needles.any { name.contains(it) }
        }
        return classifyMatches(matches)
    }

    private fun classifyMatches(matches: List<HomeDevice>): HomeResolution? = when (matches.size) {
        0 -> null
        1 -> HomeResolution.Unique(matches.single())
        else -> HomeResolution.Ambiguous(matches)
    }

    private fun kindFromTokens(tokens: List<String>): DeviceKind? {
        for (token in tokens) {
            val stem = HomeNormalizer.stem(token)
            val match = KIND_LEXICON.firstOrNull { (key, _) ->
                stem.startsWith(key) || (stem.length >= MIN_SUBSTRING_LENGTH && key.startsWith(stem))
            }
            if (match != null) return match.second
        }
        return null
    }

    private fun roomFromTokens(tokens: List<String>, rooms: Collection<String>): String? {
        for (token in tokens) {
            val tokenStem = HomeNormalizer.stem(token)
            if (tokenStem.length < MIN_ROOM_STEM_LENGTH) continue
            val room = rooms.firstOrNull { roomMatches(it, token) }
            if (room != null) return room
        }
        return null
    }

    private fun roomMatches(deviceRoom: String?, requested: String): Boolean {
        if (deviceRoom == null) return false
        val a = HomeNormalizer.stem(HomeNormalizer.normalize(deviceRoom))
        val b = HomeNormalizer.stem(HomeNormalizer.normalize(requested))
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        return a.length >= MIN_ROOM_STEM_LENGTH && b.length >= MIN_ROOM_STEM_LENGTH &&
            (a.startsWith(b) || b.startsWith(a))
    }

    private const val MIN_SUBSTRING_LENGTH = 3
    private const val MIN_ROOM_STEM_LENGTH = 4

    /** Lexicon keys are normalized stems; matching is prefix-based to absorb Russian inflection. */
    private val KIND_LEXICON: List<Pair<String, DeviceKind>> = listOf(
        "свет" to DeviceKind.LIGHT,
        "лампа" to DeviceKind.LIGHT,
        "лампочк" to DeviceKind.LIGHT,
        "освещен" to DeviceKind.LIGHT,
        "люстр" to DeviceKind.LIGHT,
        "розетк" to DeviceKind.SOCKET,
        "выключател" to DeviceKind.SWITCH,
        "вентилятор" to DeviceKind.FAN,
        "кондиционер" to DeviceKind.CLIMATE,
        "климат" to DeviceKind.CLIMATE,
        "кондей" to DeviceKind.CLIMATE,
        "термостат" to DeviceKind.THERMOSTAT,
        "штор" to DeviceKind.COVER_BLIND,
        "жалюз" to DeviceKind.COVER_BLIND,
        "ролет" to DeviceKind.COVER_BLIND,
        "гараж" to DeviceKind.COVER_GARAGE,
        "двер" to DeviceKind.COVER_DOOR,
        "замок" to DeviceKind.LOCK,
        "сигнализац" to DeviceKind.ALARM_PANEL,
        "чайник" to DeviceKind.APPLIANCE_COOKING,
        "плит" to DeviceKind.APPLIANCE_COOKING,
        "духовк" to DeviceKind.APPLIANCE_COOKING,
        "мультиварк" to DeviceKind.APPLIANCE_COOKING,
        "бойлер" to DeviceKind.WATER_HEATER,
        "водонагревател" to DeviceKind.WATER_HEATER,
        "телевизор" to DeviceKind.MEDIA,
        "медиа" to DeviceKind.MEDIA,
        "пылесос" to DeviceKind.VACUUM,
    )
}

/**
 * The resolver's own RU text normalizer. Kept here (not shared with the tool
 * lane) so `home/` stays dependency-free. Pure and Android-free.
 */
internal object HomeNormalizer {

    fun normalize(raw: String): String = raw
        .lowercase()
        .replace('ё', 'е')
        .replace(PUNCTUATION, " ")
        .replace(WHITESPACE, " ")
        .trim()

    fun tokens(raw: String): List<String> = normalize(raw).split(' ').filter { it.isNotEmpty() }

    /** Strip a trailing Russian case ending to a coarse stem. Tokens shorter than the cap pass through. */
    fun stem(token: String): String {
        var stem = token
        while (stem.length > STEM_FLOOR && stem.last() in ENDINGS) {
            stem = stem.dropLast(1)
        }
        return stem
    }

    /** Token-level Cyrillic→Latin transliteration (brand names rendered in Latin). */
    fun transliterate(raw: String): String =
        raw.lowercase().map { CYRILLIC_TO_LATIN[it] ?: it }.joinToString("")

    private const val STEM_FLOOR = 3

    private val PUNCTUATION = Regex("[^\\p{L}\\p{N}\\s]")
    private val WHITESPACE = Regex("\\s+")
    private val ENDINGS: Set<Char> = setOf('а', 'е', 'и', 'о', 'у', 'ы', 'ю', 'я', 'й', 'ь')

    private val CYRILLIC_TO_LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "i", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )
}
