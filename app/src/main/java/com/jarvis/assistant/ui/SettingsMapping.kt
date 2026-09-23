package com.jarvis.assistant.ui

/**
 * Pure setting ↔ control mappings (U1).
 *
 * These were inline expressions scattered through `SettingsActivity`. Two of
 * them encode behavior that is easy to get wrong and impossible to see:
 *
 *  - the preferred music player accepts TWO Yandex package names on read
 *    (`ru.yandex.music` and the older `com.yandex.music`) but writes only the
 *    canonical one, so a stored legacy value is silently upgraded the next
 *    time the user touches the radio;
 *  - the quiet-hour steppers wrap around midnight, which is modular arithmetic
 *    that looks correct until it hits `0 - 1`.
 *
 * Extracted so they can be pinned by plain JVM tests without Robolectric —
 * `SettingsActivity` itself has no test coverage, and these are the parts of
 * it that carry logic rather than view wiring.
 */
object SettingsMapping {

    /** The music players the Settings radio can represent. */
    enum class Player(val prefKey: String) {
        AUTO("auto"),
        YANDEX("ru.yandex.music"),
        ZVUK("com.zvooq.openplay"),
        VK("com.uma.musicvk"),
    }

    /** Preferred-player preference value → the radio it selects. */
    fun playerForPref(prefValue: String): Player = when (prefValue.trim()) {
        // Both Yandex package names are the same player; the legacy one is
        // still accepted so an old install does not lose its choice.
        "ru.yandex.music", "com.yandex.music" -> Player.YANDEX
        "com.zvooq.openplay" -> Player.ZVUK
        // com.vk.music is VK Music's code namespace, never an installed
        // package; it is accepted only as the legacy value older builds
        // persisted, so an old install does not lose its choice.
        "com.uma.musicvk", "com.vk.music" -> Player.VK
        else -> Player.AUTO
    }

    /** Radio chosen by the user → the preference value to persist. */
    fun playerPrefFor(player: Player): String = player.prefKey

    /** Semantic-recall embedder cycle (§12.4-3): AUTO → CLOUD → LOCAL → OFF. */
    val EMBEDDER_ORDER: List<String> = listOf("AUTO", "CLOUD", "LOCAL", "OFF")

    /**
     * The embedder the selector advances to. An unrecognized stored value
     * (e.g. from a downgraded or hand-edited pref) restarts the cycle at
     * AUTO rather than getting stuck.
     */
    fun nextEmbedder(current: String): String {
        val index = EMBEDDER_ORDER.indexOf(current.trim().uppercase())
        return EMBEDDER_ORDER[(index + 1).mod(EMBEDDER_ORDER.size)]
    }

    /** Follow-up window slider position (0..10) → window in milliseconds (2..12 s). */
    fun followUpSeconds(progress: Int): Long =
        (progress + 2).coerceIn(2, 12).toLong() * 1000L

    /** Quiet-hour stepper, wrapping midnight in both directions. */
    fun quietHour(current: Int, delta: Int): Int = ((current + delta) % 24 + 24) % 24

    /** Daily proactive-quota stepper, clamped to the shipped 1..5 range. */
    fun quota(current: Int, delta: Int): Int = (current + delta).coerceIn(1, 5)

    /**
     * The TTS voice id to use: the explicit Mila radio wins; otherwise the
     * custom field, with blank falling back to Mila (the default voice).
     */
    fun selectedVoiceId(isMilaSelected: Boolean, customText: String): String =
        if (isMilaSelected) "Mila" else customText.trim().ifBlank { "Mila" }

    /** Sherpa is the only engine that hides the Porcupine block. */
    fun isSherpaEngine(engine: String): Boolean = engine.trim().lowercase() == "sherpa"
}
