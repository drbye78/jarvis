package com.jarvis.assistant.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Connectivity oracle (interface exists for JVM testing). */
interface OnlineChecker {
    fun isCurrentlyOnline(): Boolean
}

/**
 * Pure decision core (m14): a network is usable only when it has both
 * INTERNET and VALIDATED capabilities — a captive portal advertises INTERNET
 * but must not pass the offline gate. Split from the Android-bound overload so
 * JVM tests exercise the decision without framework objects.
 */
fun isNetworkUsable(hasInternet: Boolean, hasValidated: Boolean): Boolean =
    hasInternet && hasValidated

fun isNetworkUsable(capabilities: NetworkCapabilities): Boolean = isNetworkUsable(
    hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
    hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
)

class NetworkMonitor(context: Context) : OnlineChecker {
    private val cm =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isCurrentlyOnline(): Boolean {
        val active = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(active) ?: return false
        return isNetworkUsable(caps)
    }
}
