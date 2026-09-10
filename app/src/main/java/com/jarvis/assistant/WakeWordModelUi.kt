package com.jarvis.assistant

/**
 * Pure mapping between the persisted wake-word model pref and the Settings
 * radio UI (JVM-tested in WakeWordModelUiTest).
 *
 * The radio has TWO options (builtin / custom) but the pref has THREE
 * values: `builtin`, `custom_bundled` and `custom_user`. An imported .ppn
 * (the «Загрузить свой wake word…» path) writes `custom_user`, which used to
 * restore onto the custom_bundled radio (the restore only knew
 * `builtin`/`custom_bundled` and treated everything else as bundled) and to
 * be silently downgraded to `custom_bundled` by the next radio
 * toggle — the imported word quietly stopped being used.
 */
object WakeWordModelUi {

    /** True when the persisted model must restore onto the builtin radio. */
    fun isBuiltinRadio(model: String): Boolean = model == "builtin"

    /**
     * Pref value for a radio selection. Selecting the custom radio KEEPS an
     * imported .ppn (`custom_user`) instead of silently downgrading it to the
     * bundled keyword set; a re-selection of the already-selected radio does
     * not fire the listener, so this only matters after a builtin round-trip.
     * Clearing the import is a separate, explicit action (not this mapping).
     */
    fun modelForSelection(customSelected: Boolean, importedPpnPath: String?): String = when {
        !customSelected -> "builtin"
        !importedPpnPath.isNullOrBlank() -> "custom_user"
        else -> "custom_bundled"
    }
}
