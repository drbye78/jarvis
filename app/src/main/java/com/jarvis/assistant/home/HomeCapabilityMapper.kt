package com.jarvis.assistant.home

/**
 * Maps a provider's raw vocabulary to the core [DeviceKind]/[Capability]/
 * [ActionVerb] enums.
 *
 * Implementations MUST be pure and TOTAL (never throw); an unrecognized input
 * degrades to [DeviceKind.UNKNOWN]/[Capability.UNKNOWN]. [deviceClass] is the
 * provider's secondary discriminator (HA's `device_class`, used to split
 * `cover` into blind/garage/door and `sensor` into temperature/sensor).
 *
 * The default-parameter shape keeps the documented `deviceKind(rawType)` call
 * valid for providers with no secondary discriminator, while letting the HA
 * implementation honor `device_class` — this is a deliberate, minimal
 * adjustment: a `kind`-only signature cannot distinguish `cover.garage` from
 * `cover.blind`, which the risk classifier needs.
 */
interface HomeCapabilityMapper {
    val provider: HomeProviderId

    /** Total: unknown → [DeviceKind.UNKNOWN]. */
    fun deviceKind(rawType: String, deviceClass: String? = null): DeviceKind

    /** Total: unknown → [Capability.UNKNOWN]. */
    fun capability(rawType: String, instance: String?): Capability

    /**
     * The verb implied by a provider service (e.g. HA `light.turn_on`), or null
     * when the service is not a user-facing action. Default: nothing.
     */
    fun verbForService(rawService: String): ActionVerb? = null
}
