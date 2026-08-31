package com.jarvis.assistant

import com.jarvis.assistant.media.NowPlaying
import com.jarvis.assistant.media.SearchCommand
import com.jarvis.assistant.media.VoiceQuery
import com.jarvis.assistant.media.VoiceQueryMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tier 1: the structured voice query model — slot scrubbing, the
 * playFromSearch command derivation (focus wire values + extras), the legacy
 * intent extras, and the verification scoring that fixes audit M3 for good.
 */
class VoiceQueryTest {

    // ------------------------------------------------------------------
    // VoiceQuery.clean
    // ------------------------------------------------------------------

    @Test
    fun `clean collapses whitespace and trims`() {
        val vq = VoiceQuery.clean("  Кино   Группа крови  ")!!
        assertEquals("Кино Группа крови", vq.query)
    }

    @Test
    fun `clean caps the query length`() {
        val vq = VoiceQuery.clean("a".repeat(500), maxQueryLength = 200)!!
        assertEquals(200, vq.query.length)
    }

    @Test
    fun `clean caps slot length`() {
        val vq = VoiceQuery.clean("", artist = "b".repeat(500), maxSlotLength = 100)!!
        assertEquals(100, vq.artist!!.length)
    }

    @Test
    fun `clean rejects an all-blank request`() {
        assertNull(VoiceQuery.clean("   "))
        assertNull(VoiceQuery.clean("", "  ", "\t", null, " "))
    }

    @Test
    fun `clean accepts slot-only requests`() {
        val vq = VoiceQuery.clean("", artist = " Queen ")!!
        assertEquals("", vq.query)
        assertEquals("Queen", vq.artist)
        assertTrue(vq.hasSlots)
        assertFalse(vq.isEmpty)
    }

    @Test
    fun `clean accepts query-only requests`() {
        val vq = VoiceQuery.clean("Bohemian Rhapsody")!!
        assertTrue(vq.hasSlots.not())
        assertFalse(vq.isEmpty)
    }

    // ------------------------------------------------------------------
    // flatQuery — deep links / legacy / extras-ignoring players
    // ------------------------------------------------------------------

    @Test
    fun `flatQuery joins title and artist (song-first convention)`() {
        val vq = VoiceQuery.clean("Группа крови", artist = "Кино")!!
        assertEquals("Группа крови Кино", vq.flatQuery())
    }

    @Test
    fun `flatQuery of a slot-only request is the slots`() {
        val vq = VoiceQuery.clean("", artist = "Кино")!!
        assertEquals("Кино", vq.flatQuery())
    }

    @Test
    fun `flatQuery of a plain query is the query`() {
        assertEquals("Bohemian Rhapsody", VoiceQuery.clean("Bohemian Rhapsody")!!.flatQuery())
    }

    // ------------------------------------------------------------------
    // toSearchCommand — focus entry types + slot extras
    // ------------------------------------------------------------------

    @Test
    fun `plain track search stays unstructured`() {
        val cmd = VoiceQuery.clean("Bohemian Rhapsody")!!.toSearchCommand()
        assertNull(cmd.focus)
        assertTrue(cmd.extras.isEmpty())
        assertTrue(cmd.isUnstructured)
        assertEquals("Bohemian Rhapsody", cmd.query)
    }

    @Test
    fun `title plus artist sends the SONG focus with both slots`() {
        val cmd = VoiceQuery.clean("Группа крови", artist = "Кино")!!.toSearchCommand()
        assertEquals(SearchCommand.FOCUS_TITLE, cmd.focus)
        assertEquals("Кино", cmd.extras[SearchCommand.EXTRA_ARTIST])
        assertEquals("Группа крови", cmd.extras[SearchCommand.EXTRA_TITLE])
        // Flat query carries both — an extras-ignoring player still finds it.
        assertEquals("Группа крови Кино", cmd.query)
    }

    @Test
    fun `artist-only sends the artist focus`() {
        val cmd = VoiceQuery.clean("", artist = "Queen")!!.toSearchCommand()
        assertEquals(SearchCommand.FOCUS_ARTIST, cmd.focus)
        assertEquals("Queen", cmd.extras[SearchCommand.EXTRA_ARTIST])
        assertEquals("Queen", cmd.query)
    }

    @Test
    fun `album request sends the album focus`() {
        val cmd = VoiceQuery.clean("", album = "Группа крови", artist = "Кино")!!.toSearchCommand()
        assertEquals(SearchCommand.FOCUS_ALBUM, cmd.focus)
        assertEquals("Группа крови", cmd.extras[SearchCommand.EXTRA_ALBUM])
        assertEquals("Кино", cmd.extras[SearchCommand.EXTRA_ARTIST])
        assertEquals("Кино Группа крови", cmd.query)
    }

    @Test
    fun `playlist focus wins over album`() {
        val cmd = VoiceQuery.clean("", album = "X", playlist = "Для тренировки")!!.toSearchCommand()
        assertEquals(SearchCommand.FOCUS_PLAYLIST, cmd.focus)
        assertEquals("Для тренировки", cmd.extras[SearchCommand.EXTRA_PLAYLIST])
    }

    @Test
    fun `genre-only sends the genre focus`() {
        val cmd = VoiceQuery.clean("", genre = "рок")!!.toSearchCommand()
        assertEquals(SearchCommand.FOCUS_GENRE, cmd.focus)
        assertEquals("рок", cmd.extras[SearchCommand.EXTRA_GENRE])
    }

    @Test
    fun `focus wire values are the entry content types`() {
        // The Assistant contract uses MediaStore.Audio.* ENTRY_CONTENT_TYPE
        // strings, NOT plain "artist"/"album" — a wrong literal here reaches
        // every player as an unrecognized focus and silently degrades the
        // structured path to nothing.
        assertEquals("vnd.android.cursor.item/artist", SearchCommand.FOCUS_ARTIST)
        assertEquals("vnd.android.cursor.item/album", SearchCommand.FOCUS_ALBUM)
        assertEquals("vnd.android.cursor.item/playlist", SearchCommand.FOCUS_PLAYLIST)
        assertEquals("vnd.android.cursor.item/genre", SearchCommand.FOCUS_GENRE)
        assertEquals("vnd.android.cursor.item/audio", SearchCommand.FOCUS_TITLE)
    }

    // ------------------------------------------------------------------
    // toLegacyIntentExtras — S4 intent payload
    // ------------------------------------------------------------------

    @Test
    fun `legacy extras carry QUERY plus focus and slots`() {
        val cmd = VoiceQuery.clean("Группа крови", artist = "Кино")!!.toSearchCommand()
        val extras = cmd.toLegacyIntentExtras()
        assertEquals("Группа крови Кино", extras[SearchCommand.EXTRA_QUERY])
        assertEquals(SearchCommand.FOCUS_TITLE, extras[SearchCommand.EXTRA_FOCUS])
        assertEquals("Кино", extras[SearchCommand.EXTRA_ARTIST])
    }

    @Test
    fun `legacy extras of an unstructured command are just QUERY`() {
        val cmd = VoiceQuery.clean("Bohemian Rhapsody")!!.toSearchCommand()
        val extras = cmd.toLegacyIntentExtras()
        assertEquals(setOf(SearchCommand.EXTRA_QUERY), extras.keys)
    }

    // ------------------------------------------------------------------
    // VoiceQueryMatcher — normalization
    // ------------------------------------------------------------------

    @Test
    fun `normalize strips punctuation quotes and case`() {
        assertEquals(
            "ac dc",
            VoiceQueryMatcher.normalize("AC/DC"),
        )
        assertEquals("bohemian rhapsody", VoiceQueryMatcher.normalize("«Bohemian Rhapsody»"))
        assertEquals("кино группа крови", VoiceQueryMatcher.normalize("КИНО — Группа крови!"))
        assertEquals("песня", VoiceQueryMatcher.normalize("Песня…"))
        assertEquals("", VoiceQueryMatcher.normalize(null))
    }

    @Test
    fun `remastered suffixes do not sink the score`() {
        val np = NowPlaying(title = "Bohemian Rhapsody - 2011 Remaster")
        val vq = VoiceQuery.clean("Bohemian Rhapsody")!!
        assertTrue(VoiceQueryMatcher.score(np, vq) >= VoiceQueryMatcher.STRONG_THRESHOLD)
    }

    // ------------------------------------------------------------------
    // VoiceQueryMatcher — scoring matrix
    // ------------------------------------------------------------------

    @Test
    fun `exact title and artist scores one`() {
        val np = NowPlaying(title = "Группа крови", artist = "Кино")
        val vq = VoiceQuery.clean("Группа крови", artist = "Кино")!!
        assertEquals(1.0, VoiceQueryMatcher.score(np, vq), 0.001)
    }

    @Test
    fun `title match alone clears the strong threshold`() {
        val np = NowPlaying(title = "Bohemian Rhapsody", artist = "Кто-то другой")
        val vq = VoiceQuery.clean("Bohemian Rhapsody", artist = "Queen")!!
        // title 1.0*0.65 + artist 0*0.35 = 0.65 ≥ 0.5
        assertEquals(0.65, VoiceQueryMatcher.score(np, vq), 0.001)
        assertTrue(VoiceQueryMatcher.isVerified(np.copy(state = NowPlaying.STATE_PLAYING), vq, null))
    }

    @Test
    fun `artist match alone stays below the threshold`() {
        val np = NowPlaying(title = "Другая песня", artist = "Queen")
        val vq = VoiceQuery.clean("Bohemian Rhapsody", artist = "Queen")!!
        // 0.65*0 + 0.35*1.0 = 0.35 < 0.5
        assertEquals(0.35, VoiceQueryMatcher.score(np, vq), 0.001)
        assertFalse(
            VoiceQueryMatcher.isVerified(np.copy(state = NowPlaying.STATE_PLAYING), vq, null),
        )
    }

    @Test
    fun `nothing in common scores zero`() {
        val np = NowPlaying(title = "Совсем другое", artist = "Другие")
        val vq = VoiceQuery.clean("Bohemian Rhapsody", artist = "Queen")!!
        assertEquals(0.0, VoiceQueryMatcher.score(np, vq), 0.001)
    }

    @Test
    fun `request with no matchable expectation scores zero`() {
        // Slot-only playlist request: playlists never appear in track
        // metadata — no evidence, must verify by other means, never pass.
        val np = NowPlaying(title = "Что-то", artist = "Кто-то", state = NowPlaying.STATE_PLAYING)
        val vq = VoiceQuery.clean("", playlist = "Для тренировки")!!
        assertEquals(0.0, VoiceQueryMatcher.score(np, vq), 0.001)
        assertFalse(VoiceQueryMatcher.isVerified(np, vq, null))
    }

    @Test
    fun `album request scores against the album field`() {
        val np = NowPlaying(title = "Битва", artist = "Кино", album = "Группа крови")
        val vq = VoiceQuery.clean("", album = "Группа крови", artist = "Кино")!!
        assertTrue(VoiceQueryMatcher.score(np, vq) >= VoiceQueryMatcher.STRONG_THRESHOLD)
    }

    // ------------------------------------------------------------------
    // VoiceQueryMatcher — partial match + position reset
    // ------------------------------------------------------------------

    @Test
    fun `partial match needs one shared token`() {
        val vq = VoiceQuery.clean("Группа крови", artist = "Кино")!!
        assertTrue(
            VoiceQueryMatcher.partialMatch(NowPlaying(title = "Группа крови (live)"), vq),
        )
        assertFalse(
            VoiceQueryMatcher.partialMatch(NowPlaying(title = "Другая песня"), vq),
        )
        assertTrue(
            VoiceQueryMatcher.partialMatch(NowPlaying(title = "X", artist = "Кино"), vq),
        )
    }

    @Test
    fun `position reset plus partial match verifies`() {
        val vq = VoiceQuery.clean("Группа крови")!!
        val before = NowPlaying(
            title = "Группа крови", state = NowPlaying.STATE_PLAYING, positionMs = 120_000,
        )
        val now = before.copy(positionMs = 0) // same song restarted
        assertTrue(VoiceQueryMatcher.isVerified(now, vq, before))
    }

    @Test
    fun `position reset without a shared token does not verify`() {
        val vq = VoiceQuery.clean("Группа крови")!!
        val before = NowPlaying(title = "Другая", state = NowPlaying.STATE_PLAYING, positionMs = 90_000)
        val now = NowPlaying(title = "Ещё другая", state = NowPlaying.STATE_PLAYING, positionMs = 0)
        assertFalse(VoiceQueryMatcher.isVerified(now, vq, before))
    }

    @Test
    fun `not playing never verifies`() {
        val vq = VoiceQuery.clean("Группа крови")!!
        val np = NowPlaying(title = "Группа крови", artist = "Кино", state = NowPlaying.STATE_PAUSED)
        assertFalse(VoiceQueryMatcher.isVerified(np, vq, null))
    }
}
