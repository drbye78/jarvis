package com.jarvis.assistant.home.providers.ha

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeAction
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** One HA service call: the target service plus its `service_data` (sans entity_id). */
data class HaServiceCall(
    val domain: String,
    val service: String,
    val data: Map<String, JsonElement> = emptyMap(),
)

/**
 * Pure inverse of [HaCapabilityMapper]: a normalized [HomeAction] → an HA
 * service call.
 *
 * [encode] is TOTAL and FAIL-CLOSED: any `(kind, verb, capability)` combination
 * it cannot express returns null, which the backend reports as
 * [com.jarvis.assistant.home.HomeError.UNSUPPORTED]. It never guesses a service
 * name. In particular an action that needs a value the core vocabulary cannot
 * carry (a color, an HVAC mode string) is rejected rather than approximated.
 *
 * Domain resolution: the entity's native domain is used when it is a known HA
 * service domain (so `input_boolean.x` stays `input_boolean.turn_on`), else the
 * domain is derived from [HomeAction.kind]. Scaling happens here: the core
 * brightness level is 0..100 while HA's `brightness` is 0..255.
 */
object HaActionEncoder {

    private val SERVICE_DOMAINS = setOf(
        "light", "switch", "input_boolean", "fan", "climate",
        "cover", "lock", "alarm_control_panel", "media_player", "vacuum", "water_heater",
    )

    /** Domains that accept a plain `turn_on`/`turn_off` service. */
    private val TOGGLE_DOMAINS = setOf(
        "light",
        "switch",
        "input_boolean",
        "fan",
        "climate",
        "media_player",
        "vacuum",
        "water_heater",
    )

    fun encode(action: HomeAction): HaServiceCall? {
        val domain = serviceDomain(action) ?: return null
        return when (action.verb) {
            ActionVerb.TURN_ON -> toggle(domain, "turn_on", action)
            ActionVerb.TURN_OFF -> toggle(domain, "turn_off", action)
            ActionVerb.LOCK -> lockCall(domain, action, locking = true)
            ActionVerb.UNLOCK -> lockCall(domain, action, locking = false)
            ActionVerb.OPEN -> coverCall(domain, action, "open_cover")
            ActionVerb.CLOSE -> coverCall(domain, action, "close_cover")
            ActionVerb.SET_POSITION -> positionCall(domain, action)
            ActionVerb.SET_LEVEL -> levelCall(domain, action)
            ActionVerb.SET_COLOR -> colorCall(domain, action)
            ActionVerb.SET_TEMPERATURE -> temperatureCall(domain, action)
            ActionVerb.SET_FAN_SPEED -> fanCall(domain, action)
            // No string/mode payload exists in the core vocabulary: fail closed.
            ActionVerb.SET_MODE, ActionVerb.READ -> null
        }
    }

    private fun toggle(domain: String, service: String, action: HomeAction): HaServiceCall? =
        if (domain in TOGGLE_DOMAINS) HaServiceCall(domain, service, entityData(action)) else null

    private fun lockCall(domain: String, action: HomeAction, locking: Boolean): HaServiceCall? = when (domain) {
        "lock" -> HaServiceCall(domain, if (locking) "lock" else "unlock", entityData(action))
        "alarm_control_panel" ->
            HaServiceCall(domain, if (locking) "alarm_arm_home" else "alarm_disarm", entityData(action))

        else -> null
    }

    private fun coverCall(domain: String, action: HomeAction, service: String): HaServiceCall? =
        if (domain == "cover") HaServiceCall(domain, service, entityData(action)) else null

    private fun positionCall(domain: String, action: HomeAction): HaServiceCall? {
        if (domain != "cover") return null
        val position = action.level ?: return null
        return HaServiceCall(
            domain = "cover",
            service = "set_cover_position",
            data = entityData(action) + ("position" to integer(position.coerceIn(0.0, 100.0))),
        )
    }

    private fun levelCall(domain: String, action: HomeAction): HaServiceCall? {
        val level = action.level ?: return null
        return when (action.capability) {
            Capability.BRIGHTNESS -> HaServiceCall(
                domain = domain,
                service = "turn_on",
                data = entityData(action) + ("brightness" to integer(brightness(level))),
            )

            Capability.FAN_SPEED -> HaServiceCall(
                domain = domain,
                service = "set_percentage",
                data = entityData(action) + ("percentage" to integer(level.coerceIn(0.0, 100.0))),
            )

            Capability.COVER_POSITION -> positionCall(domain, action)
            else -> null
        }
    }

    private fun colorCall(domain: String, action: HomeAction): HaServiceCall? {
        // Only a color TEMPERATURE can be carried as a level; a color value has
        // no representation in HomeAction, so Capability.COLOR is rejected.
        if (action.capability != Capability.COLOR_TEMP) return null
        val kelvin = action.level ?: return null
        return HaServiceCall(
            domain = domain,
            service = "turn_on",
            data = entityData(action) + ("color_temp_kelvin" to integer(kelvin)),
        )
    }

    private fun temperatureCall(domain: String, action: HomeAction): HaServiceCall? {
        if (action.capability != Capability.TARGET_TEMPERATURE) return null
        val temperature = action.level ?: return null
        return HaServiceCall(
            domain = domain,
            service = "set_temperature",
            data = entityData(action) + ("temperature" to JsonPrimitive(temperature)),
        )
    }

    private fun fanCall(domain: String, action: HomeAction): HaServiceCall? {
        if (action.capability != Capability.FAN_SPEED) return null
        val percentage = action.level ?: return null
        return HaServiceCall(
            domain = domain,
            service = "set_percentage",
            data = entityData(action) + ("percentage" to integer(percentage.coerceIn(0.0, 100.0))),
        )
    }

    private fun serviceDomain(action: HomeAction): String? {
        val native = action.key.nativeId.substringBefore('.').lowercase()
        if (native in SERVICE_DOMAINS) return native
        return domainFor(action.kind)
    }

    private fun domainFor(kind: DeviceKind): String? = when (kind) {
        DeviceKind.LIGHT -> "light"
        DeviceKind.SOCKET, DeviceKind.SWITCH -> "switch"
        DeviceKind.FAN -> "fan"
        DeviceKind.CLIMATE, DeviceKind.THERMOSTAT -> "climate"
        DeviceKind.COVER_BLIND, DeviceKind.COVER_GARAGE, DeviceKind.COVER_DOOR -> "cover"
        DeviceKind.LOCK -> "lock"
        DeviceKind.ALARM_PANEL -> "alarm_control_panel"
        DeviceKind.MEDIA -> "media_player"
        DeviceKind.VACUUM -> "vacuum"
        DeviceKind.WATER_HEATER -> "water_heater"
        DeviceKind.SENSOR, DeviceKind.APPLIANCE_COOKING, DeviceKind.UNKNOWN -> null
    }

    private fun entityData(action: HomeAction): Map<String, JsonElement> =
        mapOf("entity_id" to JsonPrimitive(action.key.nativeId))

    /** 0..100 → 0..255, clamped on both ends. */
    private fun brightness(level: Double): Double = level.coerceIn(0.0, 100.0) / 100.0 * 255.0

    private fun integer(value: Double): JsonPrimitive = JsonPrimitive(value.roundToInt())
}
