package com.jarvis.assistant.session

import android.content.Context
import com.jarvis.assistant.R

/**
 * Runtime SPOKEN phrases for the session lane (error voice + the offline
 * gate message).
 *
 * The deep-audit project report flagged that [TurnRunner] and [SessionManager]
 * hardcoded Russian error strings — fine for the RU-first product, but it
 * blocked any i18n path and made the fully-localized English UI lie about its
 * runtime behavior. Phrases now flow through this provider:
 *
 * - Production wiring ([AndroidSpeechPhrases]) resolves the SAME values /
 *   values-en resources the UI uses — one translation mechanism, full
 *   locale parity, `phrase_*` keys.
 * - [Default] carries the Russian literals as the fallback for JVM tests and
 *   any construction path without a Context; every existing test that
 *   asserts spoken Russian keeps passing unchanged through it.
 *
 * Honest limitation (documented in RUNBOOK): the SaluteSpeech voice pool is
 * Russian; on an English locale the EN phrases are spoken by the Russian
 * voice (accented) — the SYSTEM error TTS (TextToSpeech with the device
 * locale) handles them natively.
 */
interface SpeechPhrases {
    val asrOpenFailed: String
    val asrFailed: String
    val turnTimeout: String
    val networkError: String
    val genericError: String
    val tooManyToolSteps: String
    val llmTimeout: String
    val llmFailed: String
    val offline: String
    fun wakeWordEngineError(reason: String): String

    companion object {
        /**
         * Russian fallback (the product language) — also the JVM-test default,
         * injected implicitly by [TurnRunner]/[SessionManager] parameter
         * defaults so existing string assertions stay meaningful.
         */
        val Default: SpeechPhrases = object : SpeechPhrases {
            override val asrOpenFailed = "Не удалось открыть распознавание речи."
            override val asrFailed = "Ошибка распознавания речи."
            override val turnTimeout = "Превышено время ожидания. Попробуйте ещё раз."
            override val networkError = "Ошибка сети. Проверьте подключение."
            override val genericError = "Произошла ошибка. Попробуйте ещё раз."
            override val tooManyToolSteps = "Слишком много шагов, останавливаюсь."
            override val llmTimeout = "Превышено время ожидания ответа."
            override val llmFailed = "Не удалось получить ответ от нейросети."
            override val offline = "Нет подключения к интернету. Проверьте сеть."
            override fun wakeWordEngineError(reason: String) =
                "Ошибка движка wake word: $reason"
        }
    }
}

/** Locale-aware, resource-backed implementation for the live app. */
class AndroidSpeechPhrases(private val context: Context) : SpeechPhrases {
    override val asrOpenFailed: String get() = context.getString(R.string.phrase_asr_open_failed)
    override val asrFailed: String get() = context.getString(R.string.phrase_asr_failed)
    override val turnTimeout: String get() = context.getString(R.string.phrase_turn_timeout)
    override val networkError: String get() = context.getString(R.string.phrase_network_error)
    override val genericError: String get() = context.getString(R.string.phrase_generic_error)
    override val tooManyToolSteps: String get() = context.getString(R.string.phrase_too_many_tool_steps)
    override val llmTimeout: String get() = context.getString(R.string.phrase_llm_timeout)
    override val llmFailed: String get() = context.getString(R.string.phrase_llm_failed)
    override val offline: String get() = context.getString(R.string.phrase_offline)
    override fun wakeWordEngineError(reason: String): String =
        context.getString(R.string.phrase_wake_engine_error, reason)
}
