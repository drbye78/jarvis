package com.jarvis.assistant.manage

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Resolves the device's LAN IPv4 for the R13 §14 LAN bind. Deliberately uses
 * ONLY [NetworkInterface] — no Android APIs — so the socket binds a specific
 * interface (never `0.0.0.0`, §14.3) and the logic is unit-testable.
 *
 * Returns the first UP, non-loopback, site-local IPv4 address, or `null`
 * honestly when there is none (Wi-Fi down, no route). A null MUST stop the LAN
 * listener rather than fall back to a wildcard bind.
 */
object ManagementNetwork {

    fun lanIpv4(): String? {
        val interfaces = interfacesOrEmpty()
        for (networkInterface in interfaces) {
            if (!isUsable(networkInterface)) continue
            for (address in networkInterface.inetAddresses) {
                if (address is Inet4Address && address.isSiteLocalAddress) {
                    return address.hostAddress
                }
            }
        }
        return null
    }

    @Suppress("SwallowedException") // an unreadable interface list means "no LAN address"
    private fun interfacesOrEmpty(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
    } catch (_: SocketException) {
        emptyList()
    }

    @Suppress("SwallowedException") // a flapping interface is skipped, not fatal
    private fun isUsable(networkInterface: NetworkInterface): Boolean = try {
        networkInterface.isUp && !networkInterface.isLoopback
    } catch (_: SocketException) {
        false
    }
}
