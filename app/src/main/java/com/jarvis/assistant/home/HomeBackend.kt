package com.jarvis.assistant.home

/**
 * The smart-home capability contract.
 *
 * Mirrors `GeoClient`/`WeatherClient`: a narrow interface implemented once per
 * integration (HA first; Yandex/Tuya later). All methods are cancellable
 * (barge-in): an implementation must rethrow `CancellationException` and
 * cancel any in-flight provider session.
 *
 * Errors are typed ([HomeResult.Err]), never thrown.
 */
interface HomeBackend {
    /** The integration this backend speaks. */
    val provider: HomeProviderId

    /** Enumerate the provider's controllable/observable devices. */
    suspend fun discover(): HomeResult<List<HomeDevice>>

    /** Read the current state for [keys]. Providers may batch internally. */
    suspend fun readState(keys: List<HomeDeviceKey>): HomeResult<List<HomeState>>

    /** Apply one [action] and report the per-capability outcome. */
    suspend fun apply(action: HomeAction): HomeResult<HomeActionOutcome>
}
