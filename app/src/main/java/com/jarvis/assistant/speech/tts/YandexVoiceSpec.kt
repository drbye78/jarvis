package com.jarvis.assistant.speech.tts

/**
 * The in-band `"<voice>:<role>"` voice convention for Yandex SpeechKit v3.
 *
 * The provider-neutral [TtsClient] contract carries a SINGLE `voice` string,
 * but Yandex expresses the speaker and the pronunciation role as two separate
 * `Hints` entries. Rather than widen the contract (which would leak a
 * Yandex-only concept into `SessionManager`/`TurnRunner` and every other
 * provider), the pair travels packed into that one string.
 *
 * This object is the ONE definition of that packing. It is consumed by:
 *  - [YandexSpeechTts.hintsFor] — the parser, at synthesis time;
 *  - `AppGraph.voiceSource` — the producer for the live turn lane;
 *  - `SettingsActivity` — the producer for the «Проверить голос» probe.
 *
 * Splitting the encode and the decode would let them drift, and a drifted pair
 * does not crash: it sends the role as part of the speaker name, which Yandex
 * rejects or silently synthesizes with the wrong voice. Both directions are
 * therefore pinned by a round-trip test.
 *
 * Pure (no Android, no generated protos) so it is unit-testable in plain JVM.
 */
object YandexVoiceSpec {

    /** Delimiter between the speaker name and the role. */
    const val SEPARATOR = ':'

    /**
     * Service default voice. Kept in sync with
     * [com.jarvis.assistant.config.JarvisConfig.yandexTtsVoice] by a test —
     * the config is what an install with a blank pref actually falls back to,
     * so a mismatch would make «Проверить голос» preview a different speaker
     * than the assistant then uses.
     */
    const val DEFAULT_VOICE = "marina"

    /**
     * Packs a speaker and an optional role into the single-string convention.
     *
     * A blank role — or a blank voice — yields the bare voice, because a role
     * on its own names no speaker. Callers supply the config default for a
     * blank voice; this function deliberately does NOT substitute one, so a
     * blank stays blank and the service can apply its own default.
     */
    fun join(voice: String, role: String): String {
        val speaker = voice.trim()
        val tone = role.trim()
        return if (speaker.isEmpty() || tone.isEmpty()) speaker else "$speaker$SEPARATOR$tone"
    }

    /**
     * Unpacks [spec] into its speaker and role.
     *
     * [Split.role] is null when there is no `:` at all, when the `:` is at
     * index 0 (an empty speaker — the whole string is the speaker name, garbage
     * in / garbage out rather than a silent reinterpretation), or when the role
     * is blank.
     */
    fun split(spec: String): Split {
        val separator = spec.indexOf(SEPARATOR)
        if (separator <= 0) return Split(spec, null)
        val role = spec.substring(separator + 1)
        return Split(spec.substring(0, separator), role.ifBlank { null })
    }

    /** A decoded voice spec: the speaker plus an optional role. */
    data class Split(val voice: String, val role: String?)
}
