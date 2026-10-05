package com.jarvis.assistant.home

import kotlinx.coroutines.flow.Flow

/**
 * One provider-pushed state change, normalized and CONTENT-FREE.
 *
 * [old] is null when the provider cannot say (first sight, a reconnect, or a
 * state object that did not carry the previous values). [kind] is carried so a
 * consumer can classify even when discovery never ran — the event itself names
 * the device's kind.
 */
data class HomeStateChange(
    val key: HomeDeviceKey,
    val kind: DeviceKind,
    val old: HomeState?,
    val new: HomeState,
    val atMs: Long,
)

/**
 * An OPTIONAL backend capability: providers that can push state (HA's
 * `subscribe_events`) implement it; poll-only providers (Yandex, Tuya) simply
 * do not, and the awareness lane degrades to no push rather than demanding a
 * method they cannot honour.
 *
 * [events] is a COLD flow: each collector opens, owns and tears down its own
 * subscription, and [keys] filters server-side so uncurated frames never reach
 * the caller. Cancellation tears the socket down; implementations must rethrow
 * `CancellationException`.
 */
interface HomeEventSource {
    val provider: HomeProviderId

    fun events(keys: Set<HomeDeviceKey>): Flow<HomeStateChange>
}
