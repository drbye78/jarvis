package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.HabitRuleEntity
import com.jarvis.assistant.tools.ToolStrings

/**
 * COGNITIVE_PLAN §8.4: the delivery seam — how a rendered suggestion reaches
 * the session layer. Implemented by `SessionManager::speakProactively` in
 * production; fakes in tests. `false` = the session layer refused (machine
 * not IDLE — the arbiter raced a user interaction and lost; that is the
 * system working as designed).
 */
fun interface ProactiveSpeaker {
    fun speak(text: String): Boolean
}

/**
 * COGNITIVE_PLAN §8.4: deterministic suggestion templates — NO LLM call.
 * A proposal, never an action: "Ты обычно слушаешь джаз в это время.
 * Включить?" The rendering is pure and locale-aware through the
 * [ToolStrings] seam (RU/EN parity test covers every string).
 */
object ProactivePresenter {

    /** Renders the spoken proposal for a rule. Never throws. */
    fun render(rule: HabitRuleEntity, strings: ToolStrings): String = try {
        when (rule.tool) {
            "playMusic", "searchMusic" -> strings.proactiveMusicSuggestion(
                rule.argsFingerprint.removePrefix("q:").ifBlank { "" },
            )
            "getWeather" -> strings.proactiveWeatherSuggestion(
                rule.argsFingerprint.removePrefix("city:").ifBlank { "" },
            )
            else -> strings.proactiveGenericSuggestion(strings.proactiveToolLabel(rule.tool))
        }
    } catch (_: Exception) {
        // A rendering bug must never crash the arbiter — drop the suggestion
        // (an empty string is filtered by the caller).
        ""
    }
}
