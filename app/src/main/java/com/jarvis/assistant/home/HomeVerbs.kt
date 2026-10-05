package com.jarvis.assistant.home

/**
 * Derives the user-facing [ActionVerb]s a device offers from its capability set.
 *
 * ONE table shared by every provider mapper (`HaCapabilities`, `TuyaCapabilities`)
 * so the verbs a device advertises cannot drift between ecosystems. The rules:
 *  - observation-only facets ([Capability.TEMPERATURE], [Capability.SENSOR],
 *    [Capability.UNKNOWN]) contribute no write verb;
 *  - every device is readable ([ActionVerb.READ] is always present).
 *
 * A capability that maps to a verb the tool lane cannot yet issue is still
 * listed (it is an honest description of the device), but the tools only build
 * the subset they can express.
 */
object HomeVerbs {

    fun of(capabilities: Set<Capability>): Set<ActionVerb> {
        val verbs = linkedSetOf(ActionVerb.READ)
        for (capability in capabilities) add(verbs, capability)
        return verbs
    }

    private fun add(target: MutableSet<ActionVerb>, capability: Capability) {
        when (capability) {
            Capability.ON_OFF -> {
                target += ActionVerb.TURN_ON
                target += ActionVerb.TURN_OFF
            }

            Capability.BRIGHTNESS -> target += ActionVerb.SET_LEVEL
            Capability.COLOR, Capability.COLOR_TEMP -> target += ActionVerb.SET_COLOR
            Capability.TARGET_TEMPERATURE -> target += ActionVerb.SET_TEMPERATURE
            Capability.MODE -> target += ActionVerb.SET_MODE
            Capability.FAN_SPEED -> target += ActionVerb.SET_FAN_SPEED
            Capability.LOCK -> {
                target += ActionVerb.LOCK
                target += ActionVerb.UNLOCK
            }

            Capability.COVER_POSITION -> {
                target += ActionVerb.OPEN
                target += ActionVerb.CLOSE
                target += ActionVerb.SET_POSITION
            }

            Capability.TEMPERATURE, Capability.SENSOR, Capability.UNKNOWN -> Unit
        }
    }
}
