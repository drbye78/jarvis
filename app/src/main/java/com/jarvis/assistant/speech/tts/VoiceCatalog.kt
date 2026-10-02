package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.R
import com.jarvis.assistant.speech.SpeechBackend

/**
 * What a speech backend can express. Drives UI visibility/enablement so a
 * backend's supported knobs are declared in ONE place instead of being
 * hardcoded as `== YANDEX` at each consumer.
 */
data class TtsCapabilities(
    /** Whether the backend accepts a per-voice pronunciation role. */
    val roles: Boolean,
    /** Whether the backend accepts a speaking-rate hint. */
    val speed: Boolean,
)

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
     * This is the union of every role the v3 docs list for any ru-RU voice. It
     * is used for validation and by tests; it is NOT a suggestion fallback for
     * voices with no documented roles (see [yandexRolesFor]).
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
     * (`filipp`, `madi_ru`). Role lookup is FAIL-CLOSED: a voice with no
     * documented roles offers none, and the runtime drops any role that is not
     * documented for the selected voice (an undocumented pair is a hard service
     * error, not a fallback).
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
        yandex("saule_ru", "neutral", "strict", "whisper"),
        yandex("zamira_ru", "neutral", "strict", "friendly"),
        yandex("zhanar_ru", "neutral", "strict", "friendly"),
        yandex("yulduz_ru", "neutral", "strict", "friendly", "whisper"),
    )

    /**
     * The roles DOCUMENTED for [voiceId], or an empty list when the voice is
     * unknown or documents none.
     *
     * FAIL-CLOSED: this is a whitelist, not a suggestion set. An undocumented
     * voice/role pair is a HARD service error, so callers must never widen it to
     * [YANDEX_ROLES]; use [validRoleFor] to validate a requested role.
     */
    fun yandexRolesFor(voiceId: String): List<String> =
        YANDEX_VOICES.firstOrNull { it.id == voiceId }?.roles.orEmpty()

    /**
     * The trimmed [requested] role iff [voiceId] documents it, else null.
     *
     * BOTH packed-spec producers (`AppGraph.voiceSource` and the Settings probe)
     * go through this, so the "what gets sent" policy cannot drift from the
     * catalog.
     */
    fun validRoleFor(voiceId: String, requested: String?): String? {
        val role = requested?.trim().orEmpty()
        if (role.isEmpty()) return null
        return role.takeIf { yandexRolesFor(voiceId).contains(it) }
    }

    /**
     * What [backend] can express. Exhaustive `when`, NO `else`: a new backend
     * is a compile error until it declares its capabilities.
     */
    fun capabilitiesFor(backend: SpeechBackend): TtsCapabilities = when (backend) {
        SpeechBackend.YANDEX -> TtsCapabilities(roles = true, speed = true)
        SpeechBackend.SBER -> TtsCapabilities(roles = false, speed = false)
    }

    private fun yandex(id: String, vararg roles: String) =
        TtsVoiceChoice(id = id, roles = roles.toList())
}
