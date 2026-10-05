package com.jarvis.assistant.home

/**
 * Smart-home core — pure Kotlin, NO Android / `tools` / `session` / `settings`
 * imports. See `ARCHITECTURE.md` and the `CONTROLLED` tier in
 * `tools/ToolAuthorization` for how this foundation is consumed.
 *
 * This file holds the provider-agnostic vocabulary shared by the transport,
 * resolver, risk classifier, grant store and awareness lanes. Everything here
 * is deliberately Android-free so it is JVM-testable and so the upper lanes
 * (`tools/`, `settings/`) can depend on it — never the reverse.
 */

/**
 * A selectable smart-home integration. Mirrors the weather lane's
 * `WeatherProvider`: an enum with a stable persisted [id], plus a tolerant
 * parser. The enum NAME is never persisted, so renaming a constant cannot
 * orphan a stored configuration.
 */
enum class HomeProviderId(val id: String) {
    HOME_ASSISTANT("ha"),
    YANDEX("yandex"),
    TUYA("tuya"),
    ;

    companion object {
        /** Tolerant parse: null/blank/unknown → null. The caller decides the default. */
        fun fromId(raw: String?): HomeProviderId? =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() }
    }
}

/**
 * A provider-agnostic device identity: the integration plus the device's
 * native id at that provider (e.g. HA's `light.kitchen`).
 *
 * [wire] is the stable, persisted form `"<provider.id>:<nativeId>"`, used by
 * the alias codec and the grant store. The provider is stored as its stable id
 * (never the enum name), so a wording change cannot orphan an alias or grant.
 */
data class HomeDeviceKey(val provider: HomeProviderId, val nativeId: String) {
    val wire: String get() = "${provider.id}$SEPARATOR$nativeId"

    companion object {
        const val SEPARATOR = ':'

        /** Parse [wire]; malformed / unknown provider / blank native id → null. */
        fun parseOrNull(raw: String?): HomeDeviceKey? {
            val text = raw?.trim()
            if (text.isNullOrEmpty()) return null
            val at = text.indexOf(SEPARATOR)
            if (at <= 0 || at == text.length - 1) return null
            val provider = HomeProviderId.fromId(text.substring(0, at)) ?: return null
            val nativeId = text.substring(at + 1).trim()
            if (nativeId.isEmpty()) return null
            return HomeDeviceKey(provider, nativeId)
        }
    }
}

/** The physical category a device presents, provider-independent. */
enum class DeviceKind {
    LIGHT,
    SOCKET,
    SWITCH,
    FAN,
    CLIMATE,
    THERMOSTAT,
    COVER_BLIND,
    COVER_GARAGE,
    COVER_DOOR,
    LOCK,
    ALARM_PANEL,
    APPLIANCE_COOKING,
    WATER_HEATER,
    MEDIA,
    VACUUM,
    SENSOR,
    UNKNOWN,
}

/** A controllable/observable facet of a device. */
enum class Capability {
    ON_OFF,
    BRIGHTNESS,
    COLOR,
    COLOR_TEMP,
    TEMPERATURE,
    TARGET_TEMPERATURE,
    MODE,
    FAN_SPEED,
    LOCK,
    COVER_POSITION,
    SENSOR,
    UNKNOWN,
}

/** A user-requestable operation on a device. */
enum class ActionVerb {
    READ,
    TURN_ON,
    TURN_OFF,
    SET_LEVEL,
    SET_COLOR,
    SET_TEMPERATURE,
    SET_MODE,
    SET_FAN_SPEED,
    LOCK,
    UNLOCK,
    OPEN,
    CLOSE,
    SET_POSITION,
}

/**
 * One discovered device.
 *
 * [raw] is DIAGNOSTICS-ONLY: never placed in an LLM prompt and never logged at
 * INFO+ (it can carry provider payloads such as entity attributes).
 */
data class HomeDevice(
    val key: HomeDeviceKey,
    val name: String,
    val room: String?,
    val kind: DeviceKind,
    val capabilities: Set<Capability>,
    val verbs: Set<ActionVerb>,
    val raw: Map<String, String> = emptyMap(),
)

/**
 * A requested action against a device. [kind] is carried so the risk classifier
 * can be total even when the caller only holds a device handle.
 */
data class HomeAction(
    val key: HomeDeviceKey,
    val kind: DeviceKind,
    val capability: Capability,
    val verb: ActionVerb,
    val level: Double? = null,
)

/** A value read for one capability. */
sealed interface HomeValue {
    data class Bool(val value: Boolean) : HomeValue

    data class Level(val value: Double) : HomeValue

    data class Text(val value: String) : HomeValue

    data object Unsupported : HomeValue
}

/**
 * The observed state of one device.
 *
 * [raw] is DIAGNOSTICS-ONLY: never a prompt, never logged at INFO+.
 */
data class HomeState(
    val key: HomeDeviceKey,
    val values: Map<Capability, HomeValue>,
    val raw: Map<String, String> = emptyMap(),
    val atMs: Long,
)

/** Per-capability detail of an applied (or rejected) action. */
data class CapabilityOutcome(
    val capability: Capability,
    val ok: Boolean,
    val detail: String? = null,
)

/** The outcome of applying an [HomeAction]; [reconciled] is the post-action read when available. */
data class HomeActionOutcome(
    val accepted: Boolean,
    val perCapability: List<CapabilityOutcome>,
    val reconciled: HomeState? = null,
)

/** Typed failure — never an exception. Mirrors `GeoError`/`WeatherOutcome`. */
enum class HomeError {
    NOT_CONFIGURED,
    AUTH,
    UNREACHABLE,
    NOT_FOUND,
    AMBIGUOUS,
    UNSUPPORTED,
    PARTIAL,
    FAILED,
}

/** Result envelope. */
sealed interface HomeResult<out T> {
    data class Ok<T>(val value: T) : HomeResult<T>

    data class Err(val error: HomeError, val detail: String? = null) : HomeResult<Nothing>
}
