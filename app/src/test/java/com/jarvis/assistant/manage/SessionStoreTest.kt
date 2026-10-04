package com.jarvis.assistant.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session lifecycle under a deterministic clock: issue/verify, absolute
 * expiry, revocation, and independent idle expiry. The raw id is never
 * retained, so the store only ever exposes the hashed key.
 */
class SessionStoreTest {

    private var now = 1_000L
    private val store = SessionStore(nowMs = { now })

    @Test
    fun `issued session verifies and ids are unique and high entropy`() {
        val first = store.issue(ttlMs = 60_000L)
        val second = store.issue(ttlMs = 60_000L)
        assertNotEquals(first, second)
        assertEquals(43, first.length) // base64url of 32 bytes, unpadded
        assertTrue(store.verify(first))
        assertTrue(store.verify(second))
        assertEquals(2, store.activeCount())
    }

    @Test
    fun `unknown and blank ids never verify`() {
        assertFalse(store.verify(null))
        assertFalse(store.verify(""))
        assertFalse(store.verify("   "))
        assertFalse(store.verify("never-issued"))
    }

    @Test
    fun `session expires at its absolute ttl`() {
        val id = store.issue(ttlMs = 100L)
        now += 99L
        assertTrue(store.verify(id))
        now += 1L
        assertFalse(store.verify(id))
        assertEquals(0, store.activeCount())
    }

    @Test
    fun `revoke removes exactly one session`() {
        val keep = store.issue(ttlMs = 60_000L)
        val drop = store.issue(ttlMs = 60_000L)
        assertTrue(store.revoke(drop))
        assertFalse(store.revoke(drop))
        assertFalse(store.verify(drop))
        assertTrue(store.verify(keep))
    }

    @Test
    fun `revokeAll drops every session`() {
        store.issue(ttlMs = 60_000L)
        store.issue(ttlMs = 60_000L)
        store.revokeAll()
        assertEquals(0, store.activeCount())
    }

    @Test
    fun `idle expiry only drops untouched sessions`() {
        val idle = store.issue(ttlMs = 60_000L)
        val active = store.issue(ttlMs = 60_000L)

        now += 100L
        assertTrue(store.verify(active)) // refresh the active one
        now += 100L

        assertEquals(1, store.expireIdle(idleMs = 150L))
        assertFalse(store.verify(idle))
        assertTrue(store.verify(active))
    }

    @Test
    fun `non-positive idle window is a no-op`() {
        store.issue(ttlMs = 60_000L)
        now += 10_000L
        assertEquals(0, store.expireIdle(0L))
        assertEquals(0, store.expireIdle(-5L))
        assertEquals(1, store.activeCount())
    }
}
