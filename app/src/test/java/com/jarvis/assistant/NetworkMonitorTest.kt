package com.jarvis.assistant

import com.jarvis.assistant.util.isNetworkUsable
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the m14 decision: a network passes the offline gate only when it is
 * both INTERNET-capable and VALIDATED (captive portals must fail).
 */
class NetworkMonitorTest {

    @Test
    fun `validated internet connection is usable`() {
        assertEquals(true, isNetworkUsable(hasInternet = true, hasValidated = true))
    }

    @Test
    fun `captive portal (unvalidated) is not usable`() {
        assertEquals(false, isNetworkUsable(hasInternet = true, hasValidated = false))
    }

    @Test
    fun `validated flag without internet capability is not usable`() {
        assertEquals(false, isNetworkUsable(hasInternet = false, hasValidated = true))
    }

    @Test
    fun `no capabilities at all is not usable`() {
        assertEquals(false, isNetworkUsable(hasInternet = false, hasValidated = false))
    }
}
