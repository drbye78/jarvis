package com.jarvis.assistant.speech

/**
 * Which cloud speech provider serves BOTH recognition and synthesis.
 *
 * Product decision (deepwork plan §5.2): ONE backend drives ASR and TTS
 * together — a mixed Sber-ASR/Yandex-TTS configuration would double the
 * credential surface for no user-visible benefit, and the Settings card
 * mirrors the existing single-choice LLM provider selector.
 *
 * Persisted as the RAW pref string ([PREF_SBER]/[PREF_YANDEX]) rather than an
 * ordinal, so reordering the enum can never silently re-point a stored
 * setting at the other provider.
 *
 * The backend is consumed at graph construction (each provider needs its own
 * channel + auth scheme), so switching it takes effect after the next service
 * restart — exactly like [com.jarvis.assistant.config.ProviderSettings.Type].
 */
enum class SpeechBackend {
    /** Sber SaluteSpeech gRPC (the original, and the default). */
    SBER,

    /** Yandex SpeechKit v3 gRPC, API-key auth. */
    YANDEX;

    companion object {
        const val PREF_SBER = "sber"
        const val PREF_YANDEX = "yandex"

        /** Default backend — Salute remains the out-of-the-box provider. */
        val DEFAULT = SBER

        /** Parse a persisted value; anything unrecognized degrades to [DEFAULT]. */
        fun fromPref(value: String?): SpeechBackend = when (value?.trim()?.lowercase()) {
            PREF_YANDEX -> YANDEX
            else -> SBER
        }

        /** The raw value to persist for [backend]. */
        fun toPref(backend: SpeechBackend): String = when (backend) {
            YANDEX -> PREF_YANDEX
            SBER -> PREF_SBER
        }
    }
}
