package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.HomeValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Normalizes a Tuya `status[]` (code→value) map into the provider-agnostic
 * [Capability] → [HomeValue] vocabulary.
 *
 * Only typed values reach `values`; enum strings and unknowns stay out (they are
 * diagnostics-only). Scales are applied here so the upper lanes see one
 * vocabulary:
 *  - brightness codes carry one of two known ranges (255 or 1000) — see
 *    [brightnessPercent];
 *  - `va_temperature` carries `scale:1`, so it is divided by 10.
 *
 * Deliberate omissions (there is no core capability for them, and inventing one
 * would be dishonest):
 *  - Fahrenheit readbacks (`temp_current_f`, `temp_set_f`) — mixing them with
 *    the Celsius codes under one [Capability.TEMPERATURE] would silently corrupt
 *    the value;
 *  - humidity (`va_humidity`, `humidity`, …) — no H capability exists, so it
 *    stays out of `values` rather than masquerading as a boolean [Capability.SENSOR]
 *    (which the notice policy reads as a trip flag).
 */
object TuyaStateNormalizer {

    private val SWITCH_CODES = setOf(
        "switch", "switch_1", "switch_2", "switch_3", "switch_4", "switch_5", "switch_6",
        "switch_led", "switch_usb1", "switch_usb2", "power",
    )

    private val BRIGHTNESS_CODES = setOf("bright_value", "bright_value_v2", "brightness")

    /** Celsius only — the Fahrenheit codes are intentionally excluded. */
    private val TEMP_CURRENT_CODES = setOf("temp_current", "current_temperature")
    private val TEMP_SET_CODES = setOf("temp_set", "water_set")

    private val POSITION_CODES = setOf("percent_state", "percent_control", "position", "cur_state", "current_position")

    /** Codes whose string value names a tripped/normal state. */
    private val SENSOR_ALARM_CODES = setOf("smoke_sensor_status", "gas_sensor_status")

    /** Codes whose value is a boolean indication. */
    private val SENSOR_BOOL_CODES = setOf("doorcontact_state", "door_opened", "doorbell", "temper_alarm", "pir")
    private val LOCK_CODES = setOf("lock_motor_state", "open_close")

    /** One code-set → (capability, value transform) rule. */
    private class Rule(
        val codes: Set<String>,
        val capability: Capability,
        val transform: (JsonElement) -> HomeValue?,
    )

    /**
     * The extraction rules, in precedence order. A code matches at most one rule
     * because the code sets are disjoint.
     */
    private val RULES = listOf(
        Rule(SWITCH_CODES, Capability.ON_OFF, ::bool),
        Rule(BRIGHTNESS_CODES, Capability.BRIGHTNESS, ::brightnessPercent),
        Rule(TEMP_SET_CODES, Capability.TARGET_TEMPERATURE, ::number),
        Rule(TEMP_CURRENT_CODES, Capability.TEMPERATURE, ::number),
        Rule(setOf("va_temperature"), Capability.TEMPERATURE, ::scaled),
        Rule(POSITION_CODES, Capability.COVER_POSITION, ::number),
        Rule(LOCK_CODES, Capability.LOCK, ::bool),
        Rule(SENSOR_ALARM_CODES, Capability.SENSOR, ::alarm),
        Rule(SENSOR_BOOL_CODES, Capability.SENSOR, ::bool),
    )

    fun values(status: Map<String, JsonElement>): Map<Capability, HomeValue> {
        val values = linkedMapOf<Capability, HomeValue>()
        for ((code, element) in status) {
            val normalized = code.lowercase()
            val rule = RULES.firstOrNull { normalized in it.codes } ?: continue
            rule.transform(element)?.let { values[rule.capability] = it }
        }
        return values
    }

    private fun bool(element: JsonElement): HomeValue? =
        (element as? JsonPrimitive)?.booleanOrNull?.let { HomeValue.Bool(it) }

    private fun number(element: JsonElement): HomeValue? =
        (element as? JsonPrimitive)?.doubleOrNull?.let { HomeValue.Level(it) }

    /** `va_temperature: 235` with `scale:1` → 23.5. */
    private fun scaled(element: JsonElement): HomeValue? =
        (element as? JsonPrimitive)?.doubleOrNull?.let { HomeValue.Level(it / 10.0) }

    /** `smoke_sensor_status: "alarm"` → Bool(true); `"normal"` → Bool(false). */
    private fun alarm(element: JsonElement): HomeValue? {
        val text = (element as? JsonPrimitive)?.contentOrNull?.lowercase() ?: return null
        return when (text) {
            "alarm" -> HomeValue.Bool(true)
            "normal" -> HomeValue.Bool(false)
            else -> null
        }
    }

    /**
     * Brightness DP codes come in two documented ranges (`25..255` for v1,
     * `10..1000` for v2). Without the device spec we cannot know which, so any
     * value ≤ 100 is read as an already-percent level, `101..255` as the v1
     * range, and anything larger as the v2 range. The result is 0..100 either
     * way — good enough for a spoken "яркость" answer, and never out of band.
     */
    private fun brightnessPercent(element: JsonElement): HomeValue? {
        val raw = (element as? JsonPrimitive)?.doubleOrNull ?: return null
        val max = when {
            raw <= 100.0 -> 100.0
            raw <= 255.0 -> 255.0
            else -> 1000.0
        }
        return HomeValue.Level((raw / max * 100.0).coerceIn(0.0, 100.0))
    }
}
