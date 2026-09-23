package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.R

/**
 * One selectable TTS voice, in a shape both backends share.
 *
 * @property id the provider's voice identifier — a Salute pool ID (`Mila`) or a
 *   Yandex v3 speaker name (`marina`). This is what gets persisted and handed to
 *   [TtsClient.synthesizeStream].
 * @property labelRes a translatable label, or null when the identifier IS the
 *   label. Sber's verified preset has Russian/English wording; Yandex v3's voice
 *   set is a closed list of API proper nouns shown verbatim, so translating them
 *   would be wrong rather than merely redundant.
 * @property roles the roles the PROVIDER DOCUMENTS for this voice, empty when it
 *   documents none (see [VoiceCatalog.yandexRolesFor]). Salute has no role
 *   concept at all, so every Sber entry leaves this empty.
 */
data class TtsVoiceChoice(
    /** Persisted in [com.jarvis.assistant.util.AppPrefs.ttsVoice] and passed to [TtsClient.synthesizeStream]. */
    val id: String,
    /** Settings label; null means "render [id] verbatim". */
    val labelRes: Int? = null,
    /** Documented roles for this voice; empty when the provider documents none. */
    val roles: List<String> = emptyList(),
)

/**
 * Per-backend TTS voice vocabularies for the Settings «Голос» card.
 *
 * The catalog is deliberately keyed BY BACKEND ([SBER_VOICES] / [YANDEX_VOICES])
 * rather than being one flat list: the two providers have disjoint voice
 * namespaces, so an id only means anything next to the client that will speak
 * it. Settings shows the block matching the active
 * [com.jarvis.assistant.speech.SpeechBackend] and never mixes the two.
 *
 * HONEST scope on the Sber side: "Mila" (→ `May_24000` in
 * [SaluteSpeechTts.mapVoice]) is the only voice this repo has VERIFIED against
 * the Salute gRPC synthesis pool. Rather than inventing pool IDs from memory
 * (they drift and produce silent synthesis failures), the catalog ships the
 * verified preset plus a free-text entry for any other Salute voice ID the user
 * knows; unknown IDs pass through [SaluteSpeechTts.mapVoice] untouched and are
 * exercised by the «Проверить голос» button before committing.
 *
 * SAMPLE-RATE constraint on the free-text path: the SynthesisRequest proto has
 * no sample-rate field, so the PCM rate follows the VOICE's pool ID (verified
 * voices name it, e.g. `May_24000` = 24 kHz). The playback chain assumes 24 kHz
 * ([com.jarvis.assistant.contracts.AudioSpec.TTS]) — a free-text voice ID naming
 * another rate plays at the wrong pitch. Detected and logged (content-free) by
 * the rate cross-check in [SaluteSpeechTts.synthesizeStream].
 *
 * Yandex ids are enumerable because v3's voice set is a documented, closed list
 * — unlike Salute's drifting pool — and each entry carries the roles the v3
 * docs list for THAT voice, so the role dropdown can stop suggesting pairs the
 * service will reject.
 */
object VoiceCatalog {

    /** Verified Salute presets. Extend ONLY with IDs confirmed against the pool. */
    val SBER_VOICES: List<TtsVoiceChoice> = listOf(
        TtsVoiceChoice(id = "Mila", labelRes = R.string.voice_mila),
    )

    /**
     * Canonical Yandex v3 role vocabulary.
     *
     * This is the union of every role the v3 docs list for any ru-RU voice; it
     * is the suggestion set for a voice whose own roles are undocumented. A
     * test pins that the union of the per-voice [TtsVoiceChoice.roles] below
     * covers this list exactly, so no role here is unreachable.
     */
    val YANDEX_ROLES: List<String> = listOf(
        "neutral",
        "good",
        "strict",
        "friendly",
        "whisper",
        "evil",
    )

    /**
     * Yandex SpeechKit v3 ru-RU voices, each with the roles its v3 entry
     * documents. `marina` is the service default. Ids are API identifiers
     * (proper nouns), not translatable UI copy, so they deliberately have no
     * string resources — hence `labelRes` is left null.
     *
     * A voice with no roles here is one the docs list WITHOUT role support
     * (e.g. `filipp`, the `*_ru` voices); that is "undocumented", not a claim
     * that the service rejects every role, which is why
     * [yandexRolesFor] still offers the full vocabulary for it.
     */
    val YANDEX_VOICES: List<TtsVoiceChoice> = listOf(
        yandex("marina", "neutral", "whisper", "friendly"),
        yandex("alena", "neutral", "good"),
        yandex("filipp"),
        yandex("ermil", "neutral", "good"),
        yandex("jane", "neutral", "good", "evil"),
        yandex("omazh", "neutral", "evil"),
        yandex("zahar", "neutral", "good"),
        yandex("dasha", "neutral", "good", "friendly"),
        yandex("julia", "neutral", "strict"),
        yandex("lera", "neutral", "friendly"),
        yandex("masha", "good", "strict", "friendly"),
        yandex("alexander", "neutral", "good"),
        yandex("kirill", "neutral", "strict", "good"),
        yandex("anton", "neutral", "good"),
        yandex("madi_ru"),
        yandex("saule_ru"),
        yandex("zamira_ru"),
        yandex("zhanar_ru"),
        yandex("yulduz_ru"),
    )

    /**
     * The roles to SUGGEST for [voiceId].
     *
     * Narrowing matters: the service rejects a voice/role pair it does not
     * support, so the dropdown for `alena` should not offer `whisper`. But the
     * suggestion list is not a whitelist — the role field stays free text,
     * because the docs are incomplete for some voices and a user who knows a
     * working pair must still be able to enter it. A voice with no documented
     * roles, or an id the catalog has never seen, therefore falls back to the
     * full [YANDEX_ROLES] vocabulary instead of an empty menu.
     */
    fun yandexRolesFor(voiceId: String): List<String> {
        val documented = YANDEX_VOICES.firstOrNull { it.id == voiceId }?.roles.orEmpty()
        return documented.ifEmpty { YANDEX_ROLES }
    }

    private fun yandex(id: String, vararg roles: String) =
        TtsVoiceChoice(id = id, roles = roles.toList())
}
