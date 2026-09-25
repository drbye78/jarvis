package com.jarvis.assistant.settings

import com.jarvis.assistant.settings.controller.AccountsSettingsController
import com.jarvis.assistant.settings.controller.BrainSettingsController
import com.jarvis.assistant.settings.controller.ListeningSettingsController
import com.jarvis.assistant.settings.controller.MemorySettingsController
import com.jarvis.assistant.settings.controller.MusicSettingsController
import com.jarvis.assistant.settings.controller.ProactivitySettingsController
import com.jarvis.assistant.settings.controller.SpeechSettingsController
import com.jarvis.assistant.settings.controller.WeatherMapsSettingsController
import com.jarvis.assistant.util.AppPrefs

/**
 * The ONE place a [SettingsCategory] becomes its [SettingsController]
 * (settings redesign).
 *
 * The `when` is exhaustive with NO `else`, deliberately: adding a
 * [SettingsCategory] must be a COMPILE error until its controller is wired,
 * exactly like the LLM-provider `when` in `AppGraph` and the speech-backend
 * `when` before it. A fallback `else` would let a new category ship as a
 * blank screen.
 *
 * Every controller shares the frozen 3-arg seam
 * `(callbacks, prefs, host)`, so this is a pure dispatch — no per-screen
 * construction logic belongs here.
 */
object SettingsControllerFactory {

    fun create(
        category: SettingsCategory,
        callbacks: SettingsCallbacks,
        prefs: AppPrefs,
        host: SettingsHost,
    ): SettingsController = when (category) {
        SettingsCategory.BRAIN -> BrainSettingsController(callbacks, prefs, host)
        SettingsCategory.SPEECH -> SpeechSettingsController(callbacks, prefs, host)
        SettingsCategory.LISTENING -> ListeningSettingsController(callbacks, prefs, host)
        SettingsCategory.WEATHER_MAPS -> WeatherMapsSettingsController(callbacks, prefs, host)
        SettingsCategory.MEMORY -> MemorySettingsController(callbacks, prefs, host)
        SettingsCategory.PROACTIVITY -> ProactivitySettingsController(callbacks, prefs, host)
        SettingsCategory.MUSIC -> MusicSettingsController(callbacks, prefs, host)
        SettingsCategory.ACCOUNTS -> AccountsSettingsController(callbacks, prefs, host)
    }
}
