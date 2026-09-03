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
 */
data class TtsVoiceChoice(
    /** Persisted in [com.jarvis.assistant.util.AppPrefs.ttsVoice] and passed to [TtsClient.synthesizeStream]. */
    val id: String,
    /** Settings label. */
    val labelRes: Int,
)

object VoiceCatalog {
    /** Verified presets. Extend ONLY with IDs confirmed against the pool. */
    val PRESETS = listOf(
        TtsVoiceChoice(id = "Mila", labelRes = R.string.voice_mila),
    )
}
