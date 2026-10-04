package com.jarvis.assistant.settings

import androidx.annotation.DrawableRes
import androidx.annotation.LayoutRes
import androidx.annotation.StringRes
import com.jarvis.assistant.R

/**
 * The Settings screen registry (settings redesign foundation).
 *
 * The category LIST host iterates [entries] and renders one row per category,
 * so this enum IS the registry — adding a screen means adding a constant here
 * and its four resources, never editing a hand-maintained index somewhere
 * else. [layoutRes] points at the frozen `screen_settings_*.xml` detail layout;
 * [titleRes]/[subtitleRes] are the `settings_cat_<id>` / `_sub` strings;
 * [iconRes] is the row glyph (`ic_cat_<id>`).
 *
 * `id` is the lowercase enum name and doubles as the string-key suffix
 * (`settings_cat_<id>`), so the names can never drift apart.
 */
enum class SettingsCategory(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @DrawableRes val iconRes: Int,
    @LayoutRes val layoutRes: Int,
) {
    BRAIN(
        id = "brain",
        titleRes = R.string.settings_cat_brain,
        subtitleRes = R.string.settings_cat_brain_sub,
        iconRes = R.drawable.ic_cat_brain,
        layoutRes = R.layout.screen_settings_brain,
    ),
    SPEECH(
        id = "speech",
        titleRes = R.string.settings_cat_speech,
        subtitleRes = R.string.settings_cat_speech_sub,
        iconRes = R.drawable.ic_cat_speech,
        layoutRes = R.layout.screen_settings_speech,
    ),
    LISTENING(
        id = "listening",
        titleRes = R.string.settings_cat_listening,
        subtitleRes = R.string.settings_cat_listening_sub,
        iconRes = R.drawable.ic_cat_listening,
        layoutRes = R.layout.screen_settings_listening,
    ),
    WEATHER_MAPS(
        id = "weather_maps",
        titleRes = R.string.settings_cat_weather_maps,
        subtitleRes = R.string.settings_cat_weather_maps_sub,
        iconRes = R.drawable.ic_cat_weather_maps,
        layoutRes = R.layout.screen_settings_weather_maps,
    ),
    MEMORY(
        id = "memory",
        titleRes = R.string.settings_cat_memory,
        subtitleRes = R.string.settings_cat_memory_sub,
        iconRes = R.drawable.ic_cat_memory,
        layoutRes = R.layout.screen_settings_memory,
    ),
    PROACTIVITY(
        id = "proactivity",
        titleRes = R.string.settings_cat_proactivity,
        subtitleRes = R.string.settings_cat_proactivity_sub,
        iconRes = R.drawable.ic_cat_proactivity,
        layoutRes = R.layout.screen_settings_proactivity,
    ),
    MUSIC(
        id = "music",
        titleRes = R.string.settings_cat_music,
        subtitleRes = R.string.settings_cat_music_sub,
        iconRes = R.drawable.ic_cat_music,
        layoutRes = R.layout.screen_settings_music,
    ),
    ACCOUNTS(
        id = "accounts",
        titleRes = R.string.settings_cat_accounts,
        subtitleRes = R.string.settings_cat_accounts_sub,
        iconRes = R.drawable.ic_cat_accounts,
        layoutRes = R.layout.screen_settings_accounts,
    ),
    MCP(
        id = "mcp",
        titleRes = R.string.settings_cat_mcp,
        subtitleRes = R.string.settings_cat_mcp_sub,
        iconRes = R.drawable.ic_cat_mcp,
        layoutRes = R.layout.screen_settings_mcp,
    ),
    MANAGEMENT(
        id = "management",
        titleRes = R.string.settings_cat_management,
        subtitleRes = R.string.settings_cat_management_sub,
        iconRes = R.drawable.ic_cat_management,
        layoutRes = R.layout.screen_settings_management,
    ),
}
