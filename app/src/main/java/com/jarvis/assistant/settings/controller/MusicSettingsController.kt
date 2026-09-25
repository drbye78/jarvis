package com.jarvis.assistant.settings.controller

import android.view.View
import android.widget.RadioGroup
import com.jarvis.assistant.R
import com.jarvis.assistant.settings.BaseSettingsController
import com.jarvis.assistant.settings.SettingsCallbacks
import com.jarvis.assistant.settings.SettingsHost
import com.jarvis.assistant.ui.SettingsMapping
import com.jarvis.assistant.util.AppPrefs

/**
 * «Музыка» / MUSIC settings screen (settings redesign, F-C).
 *
 * Ports the old `SettingsActivity.setupMusicCard` behaviour: a single radio
 * group persisting `prefs.preferredMusicPlayer` through the pure
 * [SettingsMapping] mapping (the legacy alias handling — `com.yandex.music`,
 * `com.vk.music` — is why the pref strings are never written by hand).
 *
 * All content is essential, so there is no disclosure here. The pref is read
 * lazily on every resolve by the composition root, so a change applies LIVE to
 * the next voice command — no restart and no pending banner.
 */
class MusicSettingsController(
    callbacks: SettingsCallbacks,
    prefs: AppPrefs,
    host: SettingsHost,
) : BaseSettingsController(callbacks, prefs, host) {

    private lateinit var playerGroup: RadioGroup

    override fun bind(root: View) {
        playerGroup = root.findViewById(R.id.playerGroup)
        syncFromPref()
        playerGroup.setOnCheckedChangeListener { _, checkedId ->
            prefs.preferredMusicPlayer = SettingsMapping.playerPrefFor(playerFor(checkedId))
        }
    }

    override fun onResume() {
        if (::playerGroup.isInitialized) syncFromPref()
    }

    /** Selects the stored player's radio; an unknown pref degrades to AUTO. */
    private fun syncFromPref() {
        playerGroup.check(radioFor(SettingsMapping.playerForPref(prefs.preferredMusicPlayer)))
    }

    /** Radio id → the player it denotes (anything else is AUTO). */
    private fun playerFor(checkedId: Int): SettingsMapping.Player = when (checkedId) {
        R.id.playerYandex -> SettingsMapping.Player.YANDEX
        R.id.playerZvuk -> SettingsMapping.Player.ZVUK
        R.id.playerVk -> SettingsMapping.Player.VK
        else -> SettingsMapping.Player.AUTO
    }

    /** Player → the radio id that represents it. */
    private fun radioFor(player: SettingsMapping.Player): Int = when (player) {
        SettingsMapping.Player.YANDEX -> R.id.playerYandex
        SettingsMapping.Player.ZVUK -> R.id.playerZvuk
        SettingsMapping.Player.VK -> R.id.playerVk
        SettingsMapping.Player.AUTO -> R.id.playerAuto
    }
}
