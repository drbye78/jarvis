package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeCapabilityMapper
import com.jarvis.assistant.home.HomeProviderId

/**
 * Tuya device-`category` → core [DeviceKind], and DP-code → [Capability].
 *
 * Pure and TOTAL: an unrecognized category/code degrades to
 * [DeviceKind.UNKNOWN]/[Capability.UNKNOWN], which the risk classifier turns
 * into T2 — so an unmapped Tuya product can never be silently fast-pathed.
 *
 * The category codes are Tuya's standard set (e.g. `dj`=light, `kg`=switch,
 * `cz`=socket, `cl`=curtain, `wk`=thermostat, `kt`=AC, `ms`=lock, `wsdcg`=
 * temp/humidity sensor, `mcs`=contact, `pir`=motion, `ywbj`=smoke, `rqbj`=gas).
 * These are a SUPERSET: the caller refines the capability set from the device's
 * own `functions[]`/`status[]`, which is authoritative.
 */
class TuyaCapabilityMapper : HomeCapabilityMapper {

    override val provider: HomeProviderId = HomeProviderId.TUYA

    override fun deviceKind(rawType: String, deviceClass: String?): DeviceKind =
        when (normalize(rawType)) {
            "dj", "dd", "xdd", "fwd", "tgq" -> DeviceKind.LIGHT
            "kg", "tgkg", "tdq" -> DeviceKind.SWITCH
            "cz", "pc", "zn" -> DeviceKind.SOCKET
            "fs" -> DeviceKind.FAN
            "kt" -> DeviceKind.CLIMATE
            "wk", "wkf" -> DeviceKind.THERMOSTAT
            "cl", "clkg" -> DeviceKind.COVER_BLIND
            "ms", "mk" -> DeviceKind.LOCK
            "rs" -> DeviceKind.WATER_HEATER
            "sd" -> DeviceKind.VACUUM
            "wsdcg", "mcs", "pir", "ywbj", "rqbj", "sos", "doorbell" -> DeviceKind.SENSOR
            else -> DeviceKind.UNKNOWN
        }

    /** DP code → capability. Unknown codes degrade to [Capability.UNKNOWN]. */
    override fun capability(rawType: String, instance: String?): Capability =
        when (normalize(instance)) {
            "switch", "switch_1", "switch_2", "switch_3", "switch_4", "switch_5", "switch_6",
            "switch_led", "switch_usb1", "switch_usb2", "power", "switch_microwave",
            -> Capability.ON_OFF

            "bright_value", "bright_value_v2", "brightness" -> Capability.BRIGHTNESS
            "temp_value", "temp_value_v2", "colour_data", "colour_data_v2" ->
                if (normalize(instance).startsWith("temp")) Capability.COLOR_TEMP else Capability.COLOR

            "temp_set", "temp_set_f", "water_set" -> Capability.TARGET_TEMPERATURE
            "temp_current", "temp_current_f", "va_temperature", "humidity", "humidity_value",
            "humidity_current", "va_humidity",
            -> Capability.TEMPERATURE

            "mode", "work_mode", "work_state", "hvac_mode", "mode_auto", "mode_eco", "mode_dry",
            "cleaning_mode", "collection_mode", "cistern",
            -> Capability.MODE

            "windspeed", "fan_speed_enum", "fan_speed_percent", "fan_speed", "suction", "level",
            -> Capability.FAN_SPEED

            "control", "percent_control", "percent_state", "position", "cur_state", "current_position",
            -> Capability.COVER_POSITION

            "lock_motor_state", "open_close", "lock_function_switch", "antilock_status",
            "insurance_status", "reverse_lock", "hijack", "alarm_lock",
            -> Capability.LOCK

            "doorcontact_state", "pir", "smoke_sensor_status", "gas_sensor_status",
            "smoke_sensor_value", "gas_sensor_value", "door_opened", "doorbell", "status",
            "battery_percentage", "battery_state", "battery_value", "residual_electricity",
            "fault", "temper_alarm", "electricity_left", "signal_strength",
            -> Capability.SENSOR

            else -> Capability.UNKNOWN
        }

    /**
     * The verb implied by a Tuya command code (the reverse direction used when
     * reconciling). Tuya has no service-name concept like HA, so this maps the
     * DP code to the verb it represents; unknown → null.
     */
    override fun verbForService(rawService: String): ActionVerb? = when (normalize(rawService)) {
        "switch", "switch_1", "switch_2", "switch_3", "switch_led", "switch_usb1", "power" ->
            ActionVerb.TURN_ON

        "bright_value", "bright_value_v2", "brightness" -> ActionVerb.SET_LEVEL
        "temp_value", "temp_value_v2" -> ActionVerb.SET_COLOR
        "temp_set" -> ActionVerb.SET_TEMPERATURE
        "control" -> ActionVerb.OPEN
        else -> null
    }

    private fun normalize(raw: String?): String = raw?.trim()?.lowercase() ?: ""

    companion object {
        /** The standard ON/OFF DP codes, most-preferred first. */
        val SWITCH_CODES = listOf("switch", "switch_1", "switch_led", "power")

        /** Categories whose writes are safety-critical regardless of the DP. */
        val CRITICAL_CATEGORIES = setOf("ms", "mk", "rs", "wk", "kt", "ywbj", "rqbj")
    }
}
