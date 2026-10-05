package com.jarvis.assistant.home

/**
 * The in-memory catalog shared by the resolver, risk classifier, tools and the
 * awareness lane. Pure (no coroutines): [replace] swaps in a whole new snapshot
 * atomically, which is all a discovery/state refresh needs.
 *
 * Snapshots are immutable lists/maps, so readers always see a consistent pair
 * without locking across a mutation.
 */
class HomeRepository(
    devices: List<HomeDevice> = emptyList(),
    states: Map<HomeDeviceKey, HomeState> = emptyMap(),
) {
    @Volatile
    private var deviceList: List<HomeDevice> = devices.toList()

    @Volatile
    private var stateMap: Map<HomeDeviceKey, HomeState> = states.toMap()

    /** Atomically replace the catalog and state snapshot. */
    fun replace(devices: List<HomeDevice>, states: Map<HomeDeviceKey, HomeState>) {
        deviceList = devices.toList()
        stateMap = states.toMap()
    }

    fun all(): List<HomeDevice> = deviceList

    fun states(): Map<HomeDeviceKey, HomeState> = stateMap

    fun byHandle(key: HomeDeviceKey): HomeDevice? = deviceList.firstOrNull { it.key == key }

    fun state(key: HomeDeviceKey): HomeState? = stateMap[key]
}
