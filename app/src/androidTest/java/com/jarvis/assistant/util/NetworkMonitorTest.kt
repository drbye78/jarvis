package com.jarvis.assistant.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-test that [NetworkMonitor] can be constructed with a real Android
 * [Context] and its connectivity check does not throw.
 *
 * The test does **not** assert the boolean value (the CI device may or may
 * not have network). It only verifies the code path executes without
 * crashing, which catches regressions such as missing permissions or
 * incorrect service look-ups.
 */
@RunWith(AndroidJUnit4::class)
class NetworkMonitorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun instantiation_withRealContext_doesNotThrow() {
        val monitor = NetworkMonitor(context)
        assertNotNull("NetworkMonitor instance must not be null", monitor)
    }

    @Test
    fun isCurrentlyOnline_doesNotThrow() {
        val monitor = NetworkMonitor(context)
        // The result depends on the device state; we only verify the call succeeds.
        @Suppress("UNUSED_VARIABLE")
        val online = monitor.isCurrentlyOnline()
        // If we reached here the ConnectivityManager path executed without an
        // exception — that's the smoke-test contract.
    }
}
