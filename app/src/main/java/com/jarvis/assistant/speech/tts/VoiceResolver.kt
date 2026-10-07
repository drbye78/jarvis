package com.jarvis.assistant.speech.tts

import com.jarvis.assistant.speech.SpeechBackend

/**
 * Resolves a persisted voice id into one the ACTIVE backend will actually
 * accept.
 *
 * The two speech backends have disjoint voice namespaces, and the persisted
 * values are free-text settings (a Settings field and the management API), so
 * an id from one provider can end up handed to the other. That is not a
 * cosmetic mismatch: a Sber id such as `Mila` sent to Yandex is a hard
 * `PERMISSION_DENIED: ... voice=Mila` that aborts the whole spoken sentence.
 *
 * This object is the ONE validation policy for that boundary:
 *  - SBER accepts any non-blank id (the Salute pool is documented as drifting,
 *    so a free-text id is intentional and passed through verbatim);
 *  - YANDEX accepts only an id in [VoiceCatalog.YANDEX_VOICES], canonicalized
 *    by its catalog spelling, and otherwise falls back to [fallback] (the
 *    backend default, [YandexVoiceSpec.DEFAULT_VOICE]) rather than silently
 *    forwarding an unknown speaker.
 *
 * Pure (no Android, no generated protos) so it is unit-testable in plain JVM.
 */
object VoiceResolver {

    /**
     * The canonical voice id for [backend]: [requested] when it is expressible
     * by that backend, else [fallback].
     *
     * An exhaustive `when` with no `else`: a new backend is a compile error
     * until it declares its own namespace rule.
     */
    fun resolve(backend: SpeechBackend, requested: String?, fallback: String): String =
        when (backend) {
            SpeechBackend.SBER -> requested?.trim().takeUnless { it.isNullOrEmpty() } ?: fallback
            SpeechBackend.YANDEX ->
                VoiceCatalog.YANDEX_VOICES
                    .firstOrNull { it.id.equals(requested?.trim(), ignoreCase = true) }?.id
                    ?: fallback
        }
}
