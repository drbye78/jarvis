package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeCapabilityMapper
import com.jarvis.assistant.home.HomeVerbs
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray

/**
 * Derives the capability/verb sets of a discovered HA entity from its domain,
 * kind and raw attributes.
 *
 * The mapper maps ONE attribute name to a capability; discovery must produce the
 * whole set, so this probes a bounded list of well-known HA attribute names and
 * unions the results. Total: an unrecognized domain/attribute simply yields
 * fewer capabilities, never an error.
 */
object HaCapabilities {

    /** HA `supported_color_modes` members that imply a color-temperature control. */
    private val COLOR_TEMP_MODES = setOf("color_temp", "color_temp_kelvin")

    /** HA `supported_color_modes` members that imply a full-color control. */
    private val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

    /**
     * Attribute names probed for capability contributions. Bounded on purpose —
     * HA entities can carry dozens of attributes, but only these change the
     * provider-agnostic capability vocabulary.
     */
    private val PROBED_ATTRIBUTES = listOf(
        "brightness",
        "color_temp", "color_temp_kelvin", "color", "hs", "rgb", "rgbw", "rgbww", "xy",
        "percentage", "speed",
        "current_temperature", "temperature", "target_temperature",
        "hvac_mode", "mode", "preset_mode",
        "fan_mode", "fan_speed", "fan_speed_list",
        "current_position",
    )

    fun capabilities(
        domain: String,
        kind: DeviceKind,
        attributes: Map<String, JsonElement>,
        mapper: HomeCapabilityMapper,
    ): Set<Capability> {
        if (kind == DeviceKind.SENSOR) return sensorCapabilities(domain, attributes, mapper)
        if (domain == "light") return lightCapabilities(attributes)
        val capabilities = linkedSetOf<Capability>()
        addIfKnown(capabilities, mapper.capability(domain, null))
        for (attribute in PROBED_ATTRIBUTES) {
            if (attributes.containsKey(attribute)) addIfKnown(capabilities, mapper.capability(domain, attribute))
        }
        return capabilities
    }

    /**
     * A light advertises its color modes via `supported_color_modes` (usually
     * even while off, before a live `brightness` attribute exists), so the mode
     * list is authoritative for BRIGHTNESS/COLOR/COLOR_TEMP and the live
     * attributes are an additional signal.
     */
    private fun lightCapabilities(attributes: Map<String, JsonElement>): Set<Capability> {
        val modes = stringSet(attributes["supported_color_modes"])
        val capabilities = linkedSetOf(Capability.ON_OFF)
        if (attributes.containsKey("brightness") || modes.any { it != "onoff" && it != "unknown" }) {
            capabilities += Capability.BRIGHTNESS
        }
        if (modes.any { it in COLOR_TEMP_MODES } || attributes.containsKey("color_temp_kelvin") ||
            attributes.containsKey("color_temp")
        ) {
            capabilities += Capability.COLOR_TEMP
        }
        if (modes.any { it in COLOR_MODES }) capabilities += Capability.COLOR
        return capabilities
    }

    private fun stringSet(element: JsonElement?): Set<String> =
        runCatching { element?.jsonArray }
            .getOrNull()
            .orEmpty()
            .mapNotNull { HaPayloads.rawText(it)?.lowercase() }
            .toSet()

    /**
     * A sensor is read-only: temperature sensors surface [Capability.TEMPERATURE]
     * and every other device_class (or none) degrades to [Capability.SENSOR].
     */
    private fun sensorCapabilities(
        domain: String,
        attributes: Map<String, JsonElement>,
        mapper: HomeCapabilityMapper,
    ): Set<Capability> {
        val deviceClass = HaPayloads.rawText(attributes["device_class"]).orEmpty()
        val capability = mapper.capability(domain, deviceClass)
        return setOf(if (capability == Capability.UNKNOWN) Capability.SENSOR else capability)
    }

    private fun addIfKnown(target: MutableSet<Capability>, capability: Capability) {
        if (capability != Capability.UNKNOWN) target += capability
    }

    /** Every device is readable; the write verbs follow the capability set. */
    fun verbs(capabilities: Set<Capability>): Set<ActionVerb> = HomeVerbs.of(capabilities)
}
