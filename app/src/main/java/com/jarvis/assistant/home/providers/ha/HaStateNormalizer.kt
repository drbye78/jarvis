package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.HomeValue

/**
 * Normalizes one HA state object into the provider-agnostic
 * [Capability] → [HomeValue] map carried by `HomeState`.
 *
 * Only bools and levels ever reach `values` — HA mode names and other free text
 * are deliberately excluded (they remain in `HomeState.raw`, diagnostics-only).
 * Scales are normalized here so the upper lanes see one vocabulary:
 * brightness 0..255 → 0..100, cover position already 0..100.
 */
object HaStateNormalizer {

    private val ON_OFF_DOMAINS = setOf("light", "switch", "input_boolean", "fan", "media_player")
    private val TEMPERATURE_DEVICE_CLASSES = setOf("temperature")

    fun values(entity: HaEntity): Map<Capability, HomeValue> {
        val values = linkedMapOf<Capability, HomeValue>()
        onOff(entity)?.let { values[Capability.ON_OFF] = it }
        brightness(entity)?.let { values[Capability.BRIGHTNESS] = it }
        colorTemp(entity)?.let { values[Capability.COLOR_TEMP] = it }
        temperature(entity)?.let { values[Capability.TEMPERATURE] = it }
        targetTemperature(entity)?.let { values[Capability.TARGET_TEMPERATURE] = it }
        fanSpeed(entity)?.let { values[Capability.FAN_SPEED] = it }
        coverPosition(entity)?.let { values[Capability.COVER_POSITION] = it }
        lock(entity)?.let { values[Capability.LOCK] = it }
        sensor(entity)?.let { values[Capability.SENSOR] = it }
        return values
    }

    private fun onOff(entity: HaEntity): HomeValue? =
        if (entity.domain in ON_OFF_DOMAINS) HomeValue.Bool(entity.state == "on") else null

    private fun brightness(entity: HaEntity): HomeValue? {
        val raw = entity.attributeDouble("brightness") ?: return null
        return HomeValue.Level((raw.coerceIn(0.0, 255.0) / 255.0) * 100.0)
    }

    private fun colorTemp(entity: HaEntity): HomeValue? =
        (entity.attributeDouble("color_temp_kelvin") ?: entity.attributeDouble("color_temp"))?.let(HomeValue::Level)

    private fun temperature(entity: HaEntity): HomeValue? {
        entity.attributeDouble("current_temperature")?.let { return HomeValue.Level(it) }
        if (entity.domain == "sensor" && entity.attributeText("device_class") in TEMPERATURE_DEVICE_CLASSES) {
            entity.state.toDoubleOrNull()?.let { return HomeValue.Level(it) }
        }
        return null
    }

    private fun targetTemperature(entity: HaEntity): HomeValue? =
        (entity.attributeDouble("temperature") ?: entity.attributeDouble("target_temperature"))?.let(HomeValue::Level)

    private fun fanSpeed(entity: HaEntity): HomeValue? =
        entity.attributeDouble("percentage")?.let(HomeValue::Level)

    private fun coverPosition(entity: HaEntity): HomeValue? =
        entity.attributeDouble("current_position")?.let(HomeValue::Level)

    private fun lock(entity: HaEntity): HomeValue? = when (entity.domain) {
        "lock" -> HomeValue.Bool(entity.state == "locked")
        "alarm_control_panel" -> HomeValue.Bool(entity.state.startsWith("armed"))
        else -> null
    }

    private fun sensor(entity: HaEntity): HomeValue? = when (entity.domain) {
        "sensor" -> if (entity.attributeText("device_class") in TEMPERATURE_DEVICE_CLASSES) {
            null
        } else {
            entity.state.toDoubleOrNull()?.let(HomeValue::Level)
        }

        "binary_sensor" -> HomeValue.Bool(entity.state == "on")
        else -> null
    }

    private fun HaEntity.attributeDouble(name: String): Double? = attributeText(name)?.toDoubleOrNull()
}
