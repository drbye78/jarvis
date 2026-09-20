package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.R

/**
 * Y6: selectable TTS voices for the Settings «Голос» card.
 *
 * HONEST scope: "Mila" (→ `May_24000` in [SaluteSpeechTts.mapVoice]) is the
 * only voice this repo has VERIFIED against the Salute gRPC synthesis pool.
 * Rather than inventing pool IDs from memory (they drift and produce silent
 * synthesis failures), the catalog ships the verified preset plus a
 * free-text entry for any other Salute voice ID the user knows; unknown IDs
 * pass through [SaluteSpeechTts.mapVoice] untouched and are exercised by the
 * «Проверить голос» button before committing.
 *
 * SAMPLE-RATE constraint on the free-text path: the SynthesisRequest proto
 * has no sample-rate field, so the PCM rate follows the VOICE's pool ID
 * (verified voices name it, e.g. `May_24000` = 24 kHz). The playback chain
 * assumes 24 kHz ([com.jarvis.assistant.contracts.AudioSpec.TTS]) — a
 * free-text voice ID naming another rate plays at the wrong pitch. Detected
 * and logged (content-free) by the rate cross-check in
 * [SaluteSpeechTts.synthesizeStream].
 *
 * SBER vs YANDEX: the two providers have disjoint voice namespaces, so the
 * Settings card shows the catalog matching the ACTIVE backend
 * ([com.jarvis.assistant.speech.SpeechBackend]). Yandex ids are listed here
 * because v3's voice set is a documented, closed list — unlike Salute's
 * drifting pool, they are safe to enumerate.
 */
data class TtsVoiceChoice(
    /** Persisted in [com.jarvis.assistant.util.AppPrefs.ttsVoice] and passed to [TtsClient.synthesizeStream]. */
    val id: String,
    /** Settings label. */
    val labelRes: Int,
)

object VoiceCatalog {
    /** Verified Salute presets. Extend ONLY with IDs confirmed against the pool. */
    val PRESETS = listOf(
        TtsVoiceChoice(id = "Mila", labelRes = R.string.voice_mila),
    )

    /**
     * Yandex SpeechKit v3 ru-RU voices. These are the public identifiers from
     * the v3 voice list; `marina` is the service default. Shown verbatim in
     * the voice dropdown — they are API identifiers (proper nouns), not
     * translatable UI copy, so they deliberately have no string resources.
     */
    val YANDEX_VOICES = listOf(
        "marina",
        "alena",
        "filipp",
        "ermil",
        "jane",
        "omazh",
        "zahar",
        "dasha",
        "julia",
        "lera",
        "masha",
        "alexander",
        "kirill",
        "anton",
        "madi_ru",
        "saule_ru",
        "zamira_ru",
        "zhanar_ru",
        "yulduz_ru",
    )

    /**
     * Voice roles Yandex accepts as a separate `role` hint. NOT every voice
     * supports every role — the service rejects unsupported combinations at
     * synthesis time, which is why the field stays free-text with these as
     * suggestions instead of a hard dropdown.
     */
    val YANDEX_ROLES = listOf("neutral", "good", "strict", "friendly", "whisper", "evil")
}
