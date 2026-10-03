package com.jarvis.assistant

import com.jarvis.assistant.mcp.McpWarningThrottle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The first-time semantics of the MCP warning gate: true once, false after,
 * independent keys do not shadow each other, and a concurrent burst on one key
 * yields exactly one true.
 */
class McpWarningThrottleTest {

    @Test
    fun `a key is true only on first use`() {
        val throttle = McpWarningThrottle()
        assertTrue(throttle.firstTime("url:a:LOOPBACK_HOST"))
        assertFalse(throttle.firstTime("url:a:LOOPBACK_HOST"))
        assertFalse(throttle.firstTime("url:a:LOOPBACK_HOST"))
    }

    @Test
    fun `distinct keys are independent`() {
        val throttle = McpWarningThrottle()
        assertTrue(throttle.firstTime("invalid:bad json"))
        assertTrue(throttle.firstTime("url:a:LOOPBACK_HOST"))
        assertTrue(throttle.firstTime("url:a:PRIVATE_HOST"))
        assertTrue(throttle.firstTime("url:b:LOOPBACK_HOST"))
        assertFalse(throttle.firstTime("url:a:LOOPBACK_HOST"))
    }

    @Test
    fun `a concurrent burst on one key yields exactly one true`() {
        val throttle = McpWarningThrottle()
        val trues = AtomicInteger()
        val threads = (0 until 8).map { Thread { if (throttle.firstTime("same-key")) trues.incrementAndGet() } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(1, trues.get())
    }
}
