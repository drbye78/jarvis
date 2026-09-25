package com.jarvis.assistant.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The process-lifetime set of policies the user has changed but that have not
 * taken effect yet (settings redesign foundation). Drives the
 * «changes will apply after restart» banner.
 *
 * IN-MEMORY ON PURPOSE: a restart is exactly what clears it, so persisting it
 * would be self-defeating (a surviving "restart required" flag after the
 * restart is a lie). A process death therefore resolves every pending change
 * correctly — the next process reads the stored prefs and the new value is
 * already in force.
 */
object PendingChanges {

    private val mutable = MutableStateFlow<Set<ApplyPolicy>>(emptySet())

    /** Currently pending policies; collect this to show/hide the banner. */
    val changes: StateFlow<Set<ApplyPolicy>> = mutable.asStateFlow()

    /** Record that [policy]'s change is stored but not yet in effect. */
    fun mark(policy: ApplyPolicy) {
        mutable.update { it + policy }
    }

    /** Drop [policy] once its change has been applied (or reverted). */
    fun clear(policy: ApplyPolicy) {
        mutable.update { it - policy }
    }

    /** Drop everything (e.g. after the user confirms a restart). */
    fun clearAll() {
        mutable.value = emptySet()
    }
}
