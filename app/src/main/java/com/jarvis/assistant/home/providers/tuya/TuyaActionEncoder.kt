package com.jarvis.assistant.home.providers.tuya

import com.jarvis.assistant.home.ActionVerb
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeAction

/**
 * One encoded Tuya command: the DP [code] and its JSON [value] literal.
 */
data class TuyaCommand(val code: String, val value: String)

/**
 * Maps a provider-agnostic [HomeAction] to a Tuya `POST /commands` command, or
 * null when Tuya's cloud OpenAPI cannot express it.
 *
 * PURE and provider-local.
 *
 * **[functions] IS REQUIRED.** Tuya's DP codes are device-specific: a `dj` light
 * exposes `switch_led` (not `switch`), brightness may be `bright_value` (0..255)
 * or `bright_value_v2` (10..1000), and custom products use non-standard codes.
 * The caller passes the device's writable `functions[]` codes (from discovery),
 * and this encoder picks the matching code — exactly as the docs instruct
 * ("resolve against `GET /specification`").
 *
 * The notable honest refusal: **residential locks (`ms`) have no standard cloud
 * unlock DP** — Tuya's standard instruction set exposes lock *state* and
 * *configuration* only, so `LOCK`/`UNLOCK` return null and the tool reports the
 * action as unsupported rather than pretending. Do NOT invent an `unlock` code.
 */
object TuyaActionEncoder {

    /** The command for [action], or null when it is not expressible on Tuya cloud. */
    fun encode(action: HomeAction, functions: Set<String>): TuyaCommand? = when (action.verb) {
        ActionVerb.TURN_ON -> pick(functions, SWITCH_CODES)?.let { TuyaCommand(it, "true") }
        ActionVerb.TURN_OFF -> pick(functions, SWITCH_CODES)?.let { TuyaCommand(it, "false") }

        ActionVerb.SET_LEVEL ->
            if (action.capability == Capability.BRIGHTNESS) {
                brightnessCommand(action.level, functions)
            } else {
                null
            }

        ActionVerb.SET_TEMPERATURE ->
            if (action.capability == Capability.TARGET_TEMPERATURE) {
                pick(functions, TEMP_SET_CODES)?.let { TuyaCommand(it, intValue(action.level)) }
            } else {
                null
            }

        ActionVerb.OPEN -> coverCommand(action, functions, "open")
        ActionVerb.CLOSE -> coverCommand(action, functions, "close")

        // SET_POSITION/SET_MODE/SET_FAN_SPEED/SET_COLOR are not built by the tool
        // lane today (there is no way to express their value from a spoken word),
        // so they are deliberately not encoded.
        ActionVerb.SET_POSITION, ActionVerb.SET_MODE, ActionVerb.SET_FAN_SPEED, ActionVerb.SET_COLOR,
        ActionVerb.LOCK, ActionVerb.UNLOCK, ActionVerb.READ,
        -> null
    }

    /**
     * Brightness: pick the device's code and scale the 0..100 spoken level onto
     * that code's documented range. The caller's `functions[]` disambiguates v1
     * (255) from v2 (1000); if neither is present there is no brightness command.
     */
    private fun brightnessCommand(level: Double?, functions: Set<String>): TuyaCommand? {
        val code = pick(functions, BRIGHTNESS_CODES) ?: return null
        val max = if (code == "bright_value") MAX_BRIGHT_V1 else MAX_BRIGHT_V2
        val raw = ((level ?: 0.0).coerceIn(0.0, 100.0) / 100.0 * max).toInt()
        return TuyaCommand(code, raw.toString())
    }

    private fun coverCommand(action: HomeAction, functions: Set<String>, value: String): TuyaCommand? {
        if (action.capability != Capability.COVER_POSITION) return null
        if (action.kind == DeviceKind.COVER_GARAGE || action.kind == DeviceKind.COVER_DOOR) return null
        if ("control" !in functions) return null
        return TuyaCommand("control", "\"$value\"")
    }

    /** The first of [preferred] present in [available]; null when none is. */
    private fun pick(available: Set<String>, preferred: List<String>): String? =
        preferred.firstOrNull { it in available }

    private fun intValue(level: Double?): String = (level ?: 0.0).toInt().toString()

    private val SWITCH_CODES = listOf("switch", "switch_1", "switch_led", "power")
    private val BRIGHTNESS_CODES = listOf("bright_value", "bright_value_v2")
    private val TEMP_SET_CODES = listOf("temp_set", "temp_set_f")

    private const val MAX_BRIGHT_V1 = 255
    private const val MAX_BRIGHT_V2 = 1000
}
