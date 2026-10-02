package com.jarvis.assistant.speech.tts

/**
 * The in-band `"<voice>[:<role>][@<speed>]"` voice convention for Yandex
 * SpeechKit v3.
 *
 * The provider-neutral [TtsClient] contract carries a SINGLE `voice` string,
 * but Yandex expresses the speaker, the pronunciation role and the speaking
 * rate as three separate scalar `Hints` entries (`voice`, `role`, `speed`).
 * Rather than widen the contract (which would leak Yandex-only concepts into
 * `SessionManager`/`TurnRunner` and every other provider), the trio travels
 * packed into that one string.
 *
 * GRAMMAR (each optional part is omitted when unset):
 * ```
 * voice              -> no role, no speed
 * voice:role         -> role, no speed
 * voice@1.25         -> speed, no role
 * voice:role@1.25    -> all three
 * ```
 *
 * The role is separated by [SEPARATOR] (`:`) and the speed by
 * [SPEED_SEPARATOR] (`@`). A THIRD `:` must never be used for the speed:
 * [split] takes the FIRST `:` as the voice/role boundary, so `marina:good:evil`
 * deliberately carries the role `good:evil`. The speed suffix is isolated at
 * the LAST `@` (a voice id cannot contain one).
 *
 * FAIL-CLOSED: [split] never throws. A malformed, non-finite or out-of-range
 * speed decodes to `null` (no speed hint) while the voice and role text are
 * preserved. [join] emits `@<speed>` ONLY for a finite speed other than
 * [DEFAULT_SPEED], so a default-speed request stays byte-identical to the
 * pre-speed protocol.
 *
 * This object is the ONE definition of that packing. It is consumed by:
 *  - [YandexSpeechTts.hintsFor] — the parser, at synthesis time;
 *  - `AppGraph.voiceSource` — the producer for the live turn lane;
 *  - Settings — the producer for the «Проверить голос» probe.
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

    /** Delimiter between the optional role and the optional speed. */
    const val SPEED_SEPARATOR = '@'

    /** Service default speaking rate; omitted from [join] so today's wire stays identical. */
    const val DEFAULT_SPEED = 1.0f

    /** Lowest rate the v3 service accepts (per the `speed` hint contract). */
    const val MIN_SPEED = 0.1f

    /** Highest rate the v3 service accepts. */
    const val MAX_SPEED = 3.0f

    /**
     * Service default voice. Kept in sync with
     * [com.jarvis.assistant.config.JarvisConfig.yandexTtsVoice] by a test —
     * the config is what an install with a blank pref actually falls back to,
     * so a mismatch would make «Проверить голос» preview a different speaker
     * than the assistant then uses.
     */
    const val DEFAULT_VOICE = "marina"

    /**
     * Packs a speaker, an optional role and an optional speed into the single
     * string convention.
     *
     * A blank role — or a blank voice — yields the bare voice, because a role
     * on its own names no speaker. Callers supply the config default for a
     * blank voice; this function deliberately does NOT substitute one, so a
     * blank stays blank and the service can apply its own default.
     *
     * [speed] is appended as `@<speed>` only when it is finite and differs from
     * [DEFAULT_SPEED]; the numeric rendering is locale-independent
     * (`Float.toString`), never [String.format].
     */
    fun join(voice: String, role: String?, speed: Float? = null): String {
        val speaker = voice.trim()
        if (speaker.isEmpty()) return speaker
        val tone = role?.trim().orEmpty()
        val base = if (tone.isEmpty()) speaker else "$speaker$SEPARATOR$tone"
        return if (speed != null && speed.isFinite() && speed != DEFAULT_SPEED) {
            "$base$SPEED_SEPARATOR$speed"
        } else {
            base
        }
    }

    /**
     * Unpacks [spec] into its speaker, role and speed.
     *
     * [Split.role] is null when there is no `:` at all, when the `:` is at
     * index 0 (an empty speaker — the whole string is the speaker name, garbage
     * in / garbage out rather than a silent reinterpretation), or when the role
     * is blank.
     *
     * [Split.speed] is null when there is no `@`, when the `@` is at index 0
     * (same leading-separator rule), or when the suffix is malformed,
     * non-finite or outside `[MIN_SPEED, MAX_SPEED]`.
     */
    fun split(spec: String): Split {
        val at = spec.lastIndexOf(SPEED_SEPARATOR)
        if (at <= 0) return splitHead(spec, speed = null)
        val speed = parseSpeed(spec.substring(at + 1))
        return splitHead(spec.substring(0, at), speed)
    }

    /** Splits the `voice[:role]` head once the speed suffix has been peeled off. */
    private fun splitHead(head: String, speed: Float?): Split {
        val separator = head.indexOf(SEPARATOR)
        if (separator <= 0) return Split(head, null, speed)
        val role = head.substring(separator + 1)
        return Split(head.substring(0, separator), role.ifBlank { null }, speed)
    }

    /** Fail-closed speed decode: malformed / non-finite / out-of-range ⇒ null. */
    private fun parseSpeed(raw: String): Float? {
        val value = raw.toFloatOrNull() ?: return null
        return if (value.isFinite() && value in MIN_SPEED..MAX_SPEED) value else null
    }

    /** A decoded voice spec: the speaker plus an optional role and speed. */
    data class Split(val voice: String, val role: String?, val speed: Float? = null)
}
