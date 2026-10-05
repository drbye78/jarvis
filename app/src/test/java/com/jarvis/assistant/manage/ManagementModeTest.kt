package com.jarvis.assistant.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mode model is two concepts: the persisted intent ([ManagementMode]) and
 * the in-memory runtime flag ([ManagementActivation]). These tests pin the
 * activation rule (LOCALHOST auto-on, LAN needs reach, DISABLED off) and the
 * clock-driven idle auto-close that LAN alone has.
 */
class ManagementModeTest {

    @Test
    fun `ids are stable lowercase tokens`() {
        assertEquals("disabled", ManagementMode.DISABLED.id)
        assertEquals("localhost", ManagementMode.LOCALHOST.id)
        assertEquals("lan", ManagementMode.LAN.id)
    }

    @Test
    fun `fromId is tolerant and defaults to disabled`() {
        assertEquals(ManagementMode.LOCALHOST, ManagementMode.fromId("localhost"))
        assertEquals(ManagementMode.LAN, ManagementMode.fromId("  LAN  "))
        assertEquals(ManagementMode.DISABLED, ManagementMode.fromId("disabled"))
        assertEquals(ManagementMode.DISABLED, ManagementMode.fromId(null))
        assertEquals(ManagementMode.DISABLED, ManagementMode.fromId(""))
        assertEquals(ManagementMode.DISABLED, ManagementMode.fromId("nonsense"))
        assertEquals(ManagementMode.DISABLED, ManagementMode.DEFAULT)
    }

    @Test
    fun `process start rule is disabled off localhost on lan gated`() {
        assertFalse(initialManagementActive(ManagementMode.DISABLED))
        assertFalse(initialManagementActive(ManagementMode.DISABLED, explicitEnable = true))
        assertTrue(initialManagementActive(ManagementMode.LOCALHOST))
        assertTrue(initialManagementActive(ManagementMode.LOCALHOST, explicitEnable = true))
        assertFalse(initialManagementActive(ManagementMode.LAN))
        assertTrue(initialManagementActive(ManagementMode.LAN, explicitEnable = true))
    }

    @Test
    fun `localhost activates on start`() {
        val activation = ManagementActivation(nowMs = { 0L })
        activation.start(ManagementMode.LOCALHOST)
        assertEquals(ManagementMode.LOCALHOST, activation.mode)
        assertTrue(activation.isActive)
    }

    @Test
    fun `lan is inactive on start until explicitly enabled`() {
        val activation = ManagementActivation(nowMs = { 0L })
        activation.start(ManagementMode.LAN)
        assertFalse(activation.isActive)

        activation.enable()
        assertTrue(activation.isActive)
    }

    @Test
    fun `enable is a no-op while disabled and disable always closes`() {
        val activation = ManagementActivation(nowMs = { 0L })
        activation.start(ManagementMode.DISABLED)
        activation.enable()
        assertFalse(activation.isActive)

        activation.start(ManagementMode.LOCALHOST)
        activation.disable()
        assertFalse(activation.isActive)
    }

    @Test
    fun `lan closes after authenticated idle timeout`() {
        var now = 1_000L
        val activation = ManagementActivation(idleTimeoutMs = { 900L }, nowMs = { now })
        activation.start(ManagementMode.LAN, explicitEnable = true)
        assertTrue(activation.isActive)

        now += 899L
        assertFalse(activation.isIdleExpired())
        assertFalse(activation.closeIfIdle())

        now += 1L
        assertTrue(activation.isIdleExpired())
        assertTrue(activation.closeIfIdle())
        assertFalse(activation.isActive)
        assertEquals(ManagementMode.LAN, activation.mode)
    }

    @Test
    fun `touch refreshes the idle window`() {
        var now = 0L
        val activation = ManagementActivation(idleTimeoutMs = { 100L }, nowMs = { now })
        activation.start(ManagementMode.LAN, explicitEnable = true)

        now = 90L
        activation.touch()
        now = 150L
        assertFalse(activation.isIdleExpired())

        now = 190L
        assertTrue(activation.isIdleExpired())
    }

    @Test
    fun `localhost is never idle closed`() {
        var now = 0L
        val activation = ManagementActivation(idleTimeoutMs = { 100L }, nowMs = { now })
        activation.start(ManagementMode.LOCALHOST)

        now = 10_000_000L
        assertFalse(activation.isIdleExpired())
        assertFalse(activation.closeIfIdle())
        assertTrue(activation.isActive)
    }

    @Test
    fun `non-positive idle timeout disables auto close`() {
        var now = 0L
        val activation = ManagementActivation(idleTimeoutMs = { 0L }, nowMs = { now })
        activation.start(ManagementMode.LAN, explicitEnable = true)
        now = 10_000_000L
        assertFalse(activation.isIdleExpired())
        assertTrue(activation.isActive)
    }

    /**
     * The R13 P1 device finding: the idle-timeout field is advertised LIVE, so a
     * change while the listener is running must take effect WITHOUT a restart.
     * The window is therefore read on every check, not captured at construction.
     */
    @Test
    fun `idle window change applies live to a running listener`() {
        var now = 0L
        var window = 900L
        val activation = ManagementActivation(idleTimeoutMs = { window }, nowMs = { now })
        activation.start(ManagementMode.LAN, explicitEnable = true)

        // Just past the ORIGINAL window it would still be open.
        now = 100L
        window = 50L // the user lowers the timeout mid-run
        assertTrue("the lowered window must apply without a restart", activation.isIdleExpired())
        assertTrue(activation.closeIfIdle())
    }
}
