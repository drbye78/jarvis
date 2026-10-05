package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeCapabilityMapper
import com.jarvis.assistant.home.HomeProviderId

/**
 * Home Assistant domain/`device_class` → core vocabulary. Pure and total.
 *
 * HA identifies an entity by a `<domain>.<object_id>` entity id and carries a
 * secondary `attributes.device_class` for some domains. [deviceKind] takes the
 * DOMAIN plus that `device_class` so `cover` splits into blind/garage/door — the
 * distinction the risk classifier turns into T2 (a garage/door is critical, a
 * blind is reversible). [capability] takes the domain plus the attribute name
 * (passed as `instance`) so a `climate` entity yields target-temperature,
 * current-temperature, mode or fan-speed depending on the attribute.
 */
class HaCapabilityMapper : HomeCapabilityMapper {

    override val provider: HomeProviderId = HomeProviderId.HOME_ASSISTANT

    override fun deviceKind(rawType: String, deviceClass: String?): DeviceKind {
        val domain = domainOf(rawType)
        val klass = normalize(deviceClass)
        return when (domain) {
            "light" -> DeviceKind.LIGHT
            "switch", "input_boolean" -> DeviceKind.SWITCH
            "fan" -> DeviceKind.FAN
            "climate" -> if (klass == "thermostat") DeviceKind.THERMOSTAT else DeviceKind.CLIMATE
            "thermostat" -> DeviceKind.THERMOSTAT
            "cover" -> kindForCover(klass)
            "lock" -> DeviceKind.LOCK
            "alarm_control_panel" -> DeviceKind.ALARM_PANEL
            "media_player" -> DeviceKind.MEDIA
            "vacuum" -> DeviceKind.VACUUM
            "water_heater" -> DeviceKind.WATER_HEATER
            "sensor", "binary_sensor" -> DeviceKind.SENSOR
            else -> DeviceKind.UNKNOWN
        }
    }

    private fun kindForCover(deviceClass: String): DeviceKind = when (deviceClass) {
        "garage", "garage_door" -> DeviceKind.COVER_GARAGE
        "door" -> DeviceKind.COVER_DOOR
        else -> DeviceKind.COVER_BLIND
    }

    override fun capability(rawType: String, instance: String?): Capability {
        val domain = domainOf(rawType)
        val attr = normalize(instance)
        return when (domain) {
            "light" -> lightCapability(attr)
            "switch", "input_boolean" -> Capability.ON_OFF
            "fan" -> if (attr == "percentage" || attr == "speed") Capability.FAN_SPEED else Capability.ON_OFF
            "climate", "thermostat" -> climateCapability(attr)
            "cover" -> Capability.COVER_POSITION
            "lock", "alarm_control_panel" -> Capability.LOCK
            "water_heater" -> Capability.TARGET_TEMPERATURE
            "media_player", "vacuum" -> Capability.ON_OFF
            "sensor", "binary_sensor" -> if (attr == "temperature") Capability.TEMPERATURE else Capability.SENSOR
            else -> Capability.UNKNOWN
        }
    }

    private fun lightCapability(attr: String): Capability = when (attr) {
        "brightness" -> Capability.BRIGHTNESS
        "color_temp", "color_temp_kelvin" -> Capability.COLOR_TEMP
        "color", "colour", "hs", "rgb", "rgbw", "rgbww", "xy" -> Capability.COLOR
        else -> Capability.ON_OFF
    }

    private fun climateCapability(attr: String): Capability = when (attr) {
        "current_temperature" -> Capability.TEMPERATURE
        "temperature", "target_temperature" -> Capability.TARGET_TEMPERATURE
        "hvac_mode", "mode", "preset_mode" -> Capability.MODE
        "fan_mode", "fan_speed", "fan_speed_list" -> Capability.FAN_SPEED
        else -> Capability.TARGET_TEMPERATURE
    }

    override fun verbForService(rawService: String): ActionVerb? {
        // Accept both `light.turn_on` and a bare `turn_on`.
        val service = normalize(rawService).substringAfterLast('.')
        return when (service) {
            "turn_on", "toggle" -> ActionVerb.TURN_ON
            "turn_off", "stop" -> ActionVerb.TURN_OFF
            "set_brightness", "set_percentage", "set_position" -> ActionVerb.SET_LEVEL
            "open_cover", "open" -> ActionVerb.OPEN
            "close_cover", "close" -> ActionVerb.CLOSE
            "set_cover_position" -> ActionVerb.SET_POSITION
            "lock", "alarm_arm_home", "alarm_arm_away", "alarm_arm_night" -> ActionVerb.LOCK
            "unlock", "alarm_disarm" -> ActionVerb.UNLOCK
            "set_temperature", "set_target_temperature" -> ActionVerb.SET_TEMPERATURE
            "set_hvac_mode", "set_mode", "set_preset_mode" -> ActionVerb.SET_MODE
            "set_fan_mode", "set_speed", "set_fan_speed" -> ActionVerb.SET_FAN_SPEED
            "set_color", "set_color_temp" -> ActionVerb.SET_COLOR
            else -> null
        }
    }

    private fun normalize(raw: String?): String = raw?.trim()?.lowercase() ?: ""

    /** Accept either a bare domain (`light`) or a full entity id (`light.kitchen`). */
    private fun domainOf(raw: String?): String = normalize(raw).substringBefore('.')
}
