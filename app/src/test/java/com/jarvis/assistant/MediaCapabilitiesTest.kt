package com.jarvis.assistant

import com.jarvis.assistant.media.MediaCapabilities
import com.jarvis.assistant.media.MediaCapabilities.Companion.RATING_HEART
import com.jarvis.assistant.media.TransportAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 0: the action-mask decoder is the foundation every capability gate
 * builds on — unknown bits must be ignored, an empty mask must fail OPEN,
 * and compat bits (repeat/shuffle/speed) must decode from the same long.
 */
class MediaCapabilitiesTest {

    @Test
    fun `framework bits decode to their actions`() {
        // play | pause | playFromSearch | skipToNext | seekTo | setRating
        val mask = MediaCapabilities.ACTION_PLAY or
            MediaCapabilities.ACTION_PAUSE or
            MediaCapabilities.ACTION_PLAY_FROM_SEARCH or
            MediaCapabilities.ACTION_SKIP_TO_NEXT or
            MediaCapabilities.ACTION_SEEK_TO or
            MediaCapabilities.ACTION_SET_RATING

        val caps = MediaCapabilities.fromActionMask(mask, ratingType = RATING_HEART, hasQueue = true)

        assertTrue(caps.known)
        assertTrue(caps.supports(TransportAction.PLAY))
        assertTrue(caps.supports(TransportAction.PAUSE))
        assertTrue(caps.supports(TransportAction.PLAY_FROM_SEARCH))
        assertTrue(caps.supports(TransportAction.SEEK_TO))
        assertTrue(caps.supports(TransportAction.SET_RATING))
        assertFalse(caps.supports(TransportAction.PLAY_FROM_MEDIA_ID))
        assertFalse(caps.supports(TransportAction.STOP))
        assertEquals(RATING_HEART, caps.ratingType)
        assertTrue(caps.hasQueue)
    }

    @Test
    fun `compat bits decode from the same mask`() {
        // A MediaSessionCompat player ORs repeat/shuffle/speed into the same field.
        val mask = MediaCapabilities.ACTION_PLAY or
            MediaCapabilities.ACTION_SET_REPEAT_MODE or
            MediaCapabilities.ACTION_SET_SHUFFLE_MODE or
            MediaCapabilities.ACTION_SET_PLAYBACK_SPEED

        val caps = MediaCapabilities.fromActionMask(mask)

        assertTrue(caps.supports(TransportAction.SET_REPEAT_MODE))
        assertTrue(caps.supports(TransportAction.SET_SHUFFLE_MODE))
        assertTrue(caps.supports(TransportAction.SET_PLAYBACK_SPEED))
        assertFalse(caps.supports(TransportAction.PAUSE))
    }

    @Test
    fun `prepare bits decode - API 24 surface`() {
        val mask = MediaCapabilities.ACTION_PREPARE or
            MediaCapabilities.ACTION_PREPARE_FROM_SEARCH or
            MediaCapabilities.ACTION_PREPARE_FROM_MEDIA_ID

        val caps = MediaCapabilities.fromActionMask(mask)

        assertTrue(caps.supports(TransportAction.PREPARE))
        assertTrue(caps.supports(TransportAction.PREPARE_FROM_SEARCH))
        assertTrue(caps.supports(TransportAction.PREPARE_FROM_MEDIA_ID))
        assertFalse(caps.supports(TransportAction.PLAY_FROM_SEARCH))
    }

    @Test
    fun `unknown high bits are ignored not rejected`() {
        // A future framework or private custom action bit — the decoder must
        // still report the bits it knows, never crash, never guess.
        val mask = MediaCapabilities.ACTION_PLAY or (1L shl 40) or (1L shl 33)
        val caps = MediaCapabilities.fromActionMask(mask)
        assertTrue(caps.known)
        assertTrue(caps.supports(TransportAction.PLAY))
        assertEquals(setOf(TransportAction.PLAY), caps.supported)
    }

    @Test
    fun `zero mask means unknown and fails open`() {
        val caps = MediaCapabilities.fromActionMask(0L)
        assertFalse(caps.known)
        // Permissive: a session that has not published a PlaybackState yet
        // must not have its features disabled by the gate.
        assertTrue(caps.supports(TransportAction.PLAY_FROM_SEARCH))
        assertTrue(caps.supports(TransportAction.SET_REPEAT_MODE))
    }

    @Test
    fun `unknown handle default is permissive`() {
        val caps = MediaCapabilities.UNKNOWN
        assertFalse(caps.known)
        assertTrue(caps.supports(TransportAction.SEEK_TO))
        assertEquals(0L, caps.mask)
    }

    @Test
    fun `describe is logcat-readable`() {
        val mask = MediaCapabilities.ACTION_PLAY or MediaCapabilities.ACTION_PLAY_FROM_SEARCH
        val caps = MediaCapabilities.fromActionMask(mask, ratingType = RATING_HEART, hasQueue = true)
        val d = caps.describe()
        assertTrue(d.contains("playFromSearch"))
        assertTrue(d.contains("heart"))
        assertTrue(d.contains("queue=true"))
    }

    @Test
    fun `full assistant-style mask decodes every expected action`() {
        // What an Assistant-compliant player typically publishes.
        val mask = MediaCapabilities.ACTION_STOP or
            MediaCapabilities.ACTION_PAUSE or
            MediaCapabilities.ACTION_PLAY or
            MediaCapabilities.ACTION_SKIP_TO_PREVIOUS or
            MediaCapabilities.ACTION_SKIP_TO_NEXT or
            MediaCapabilities.ACTION_SEEK_TO or
            MediaCapabilities.ACTION_PLAY_PAUSE or
            MediaCapabilities.ACTION_PLAY_FROM_MEDIA_ID or
            MediaCapabilities.ACTION_PLAY_FROM_SEARCH or
            MediaCapabilities.ACTION_SKIP_TO_QUEUE_ITEM or
            MediaCapabilities.ACTION_SET_RATING or
            MediaCapabilities.ACTION_SET_REPEAT_MODE or
            MediaCapabilities.ACTION_SET_SHUFFLE_MODE or
            MediaCapabilities.ACTION_SET_PLAYBACK_SPEED

        val caps = MediaCapabilities.fromActionMask(mask, ratingType = RATING_HEART, hasQueue = true)

        for (action in listOf(
            TransportAction.STOP, TransportAction.PAUSE, TransportAction.PLAY,
            TransportAction.SKIP_TO_PREVIOUS, TransportAction.SKIP_TO_NEXT,
            TransportAction.SEEK_TO, TransportAction.PLAY_PAUSE,
            TransportAction.PLAY_FROM_MEDIA_ID, TransportAction.PLAY_FROM_SEARCH,
            TransportAction.SKIP_TO_QUEUE_ITEM, TransportAction.SET_RATING,
            TransportAction.SET_REPEAT_MODE, TransportAction.SET_SHUFFLE_MODE,
            TransportAction.SET_PLAYBACK_SPEED,
        )) {
            assertTrue("expected $action", caps.supports(action))
        }
        // Rating + queue: the «лайкни» and «что в очереди» surfaces.
        assertEquals(RATING_HEART, caps.ratingType)
        assertTrue(caps.hasQueue)
    }
}
