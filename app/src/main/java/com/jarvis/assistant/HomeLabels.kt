package com.jarvis.assistant

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.DeviceKind

/**
 * Shared resource mappings for the smart-home surfaces (R4/H2–H3). Pure lookups
 * over the provider-agnostic enums, kept in one place so the device browser and
 * the grants editor cannot drift. UNKNOWN/generic always has a label.
 */
internal object HomeLabels {

    fun kindRes(kind: DeviceKind): Int = when (kind) {
        DeviceKind.LIGHT -> R.string.settings_home_kind_light
        DeviceKind.SOCKET -> R.string.settings_home_kind_socket
        DeviceKind.SWITCH -> R.string.settings_home_kind_switch
        DeviceKind.FAN -> R.string.settings_home_kind_fan
        DeviceKind.CLIMATE -> R.string.settings_home_kind_climate
        DeviceKind.THERMOSTAT -> R.string.settings_home_kind_thermostat
        DeviceKind.COVER_BLIND -> R.string.settings_home_kind_cover_blind
        DeviceKind.COVER_GARAGE -> R.string.settings_home_kind_cover_garage
        DeviceKind.COVER_DOOR -> R.string.settings_home_kind_cover_door
        DeviceKind.LOCK -> R.string.settings_home_kind_lock
        DeviceKind.ALARM_PANEL -> R.string.settings_home_kind_alarm
        DeviceKind.APPLIANCE_COOKING -> R.string.settings_home_kind_appliance
        DeviceKind.WATER_HEATER -> R.string.settings_home_kind_water_heater
        DeviceKind.MEDIA -> R.string.settings_home_kind_media
        DeviceKind.VACUUM -> R.string.settings_home_kind_vacuum
        DeviceKind.SENSOR -> R.string.settings_home_kind_sensor
        DeviceKind.UNKNOWN -> R.string.settings_home_kind_unknown
    }

    fun actionRes(verb: ActionVerb): Int = when (verb) {
        ActionVerb.TURN_ON -> R.string.settings_home_action_turn_on
        ActionVerb.TURN_OFF -> R.string.settings_home_action_turn_off
        ActionVerb.SET_LEVEL -> R.string.settings_home_action_set_level
        ActionVerb.SET_TEMPERATURE -> R.string.settings_home_action_set_temperature
        ActionVerb.SET_MODE -> R.string.settings_home_action_set_mode
        ActionVerb.SET_FAN_SPEED -> R.string.settings_home_action_set_fan_speed
        ActionVerb.SET_COLOR -> R.string.settings_home_action_set_color
        ActionVerb.READ, ActionVerb.LOCK, ActionVerb.UNLOCK, ActionVerb.OPEN, ActionVerb.CLOSE,
        ActionVerb.SET_POSITION -> R.string.settings_home_action_generic
    }
}
