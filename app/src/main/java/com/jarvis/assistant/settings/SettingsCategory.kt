package com.jarvis.assistant.settings

import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.jarvis.assistant.R

/**
 * The Settings screen registry (settings redesign foundation).
 *
 * The category LIST host iterates [entries] and renders one row per category,
 * so this enum IS the registry — adding a screen means adding a constant here
 * and its three resources, never editing a hand-maintained index somewhere
 * else. [layoutRes] points at the frozen `screen_settings_*.xml` detail layout;
 * [titleRes]/[subtitleRes] are the `settings_cat_<id>` / `_sub` strings.
 *
 * `id` is the lowercase enum name and doubles as the string-key suffix
 * (`settings_cat_<id>`), so the three names can never drift apart.
 */
enum class SettingsCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @LayoutRes val layoutRes: Int,
) {
    BRAIN(
        id = "brain",
        titleRes = R.string.settings_cat_brain,
        subtitleRes = R.string.settings_cat_brain_sub,
        layoutRes = R.layout.screen_settings_brain,
    ),
    SPEECH(
        id = "speech",
        titleRes = R.string.settings_cat_speech,
        subtitleRes = R.string.settings_cat_speech_sub,
        layoutRes = R.layout.screen_settings_speech,
    ),
    LISTENING(
        id = "listening",
        titleRes = R.string.settings_cat_listening,
        subtitleRes = R.string.settings_cat_listening_sub,
        layoutRes = R.layout.screen_settings_listening,
    ),
    WEATHER_MAPS(
        id = "weather_maps",
        titleRes = R.string.settings_cat_weather_maps,
        subtitleRes = R.string.settings_cat_weather_maps_sub,
        layoutRes = R.layout.screen_settings_weather_maps,
    ),
    MEMORY(
        id = "memory",
        titleRes = R.string.settings_cat_memory,
        subtitleRes = R.string.settings_cat_memory_sub,
        layoutRes = R.layout.screen_settings_memory,
    ),
    PROACTIVITY(
        id = "proactivity",
        titleRes = R.string.settings_cat_proactivity,
        subtitleRes = R.string.settings_cat_proactivity_sub,
        layoutRes = R.layout.screen_settings_proactivity,
    ),
    MUSIC(
        id = "music",
        titleRes = R.string.settings_cat_music,
        subtitleRes = R.string.settings_cat_music_sub,
        layoutRes = R.layout.screen_settings_music,
    ),
    ACCOUNTS(
        id = "accounts",
        titleRes = R.string.settings_cat_accounts,
        subtitleRes = R.string.settings_cat_accounts_sub,
        layoutRes = R.layout.screen_settings_accounts,
    ),
}
