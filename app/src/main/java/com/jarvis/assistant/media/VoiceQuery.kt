package com.jarvis.assistant.media

/**
 * Tier 1: a structured voice music request and the scoring that decides
 * whether what is NOW playing matches what was ASKED.
 *
 * The Assistant voice-search contract distinguishes structured requests
 * ("включи альбом Группа крови Кино" → album + artist slots) from flat text.
 * Structured slots flow to the player as playFromSearch extras
 * (MediaStore.EXTRA_MEDIA_FOCUS + slot extras, assembled by the adapter from
 * [toSearchCommand]); they also make verification query-aware, which is the
 * full audit-M3 fix: PLAYING is only ever spoken when the now-playing
 * metadata actually matches the request.
 */
data class VoiceQuery(
    /** Flat search text (track-ish). May be blank when only slots are set. */
    val query: String,
    val artist: String? = null,
    val album: String? = null,
    val playlist: String? = null,
    val genre: String? = null,
) {
    val hasSlots: Boolean
        get() = listOf(artist, album, playlist, genre).any { !it.isNullOrBlank() }

    /** Nothing usable was requested. */
    val isEmpty: Boolean get() = query.isBlank() && !hasSlots

    /** Flat text for deep links, the legacy search intent, and extras-ignoring
     * players: every non-blank field joined (title first — the old
     * "song + artist" convention deep-link search engines expect). */
    fun flatQuery(): String =
        (listOf(query, artist, album, playlist, genre))
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")

    /**
     * The playFromSearch command derived from this request, per the
     * Assistant interop table (developer.android.com/media/implement/assistant):
     * a focus entry type with its slot extras; null focus = plain
     * unstructured search (flat query, no extras).
     *
     * Focus priority: playlist > album > song-by-artist > artist > genre >
     * unstructured. The subtle case is title + artist ("включи Группа крови
     * группы Кино"): sending the ARTIST focus makes compliant players start
     * an artist queue, but the user asked for the SONG — so a set title wins
     * and the artist rides along as a slot extra.
     */
    fun toSearchCommand(): SearchCommand {
        val focus = when {
            !playlist.isNullOrBlank() -> SearchCommand.FOCUS_PLAYLIST
            !album.isNullOrBlank() -> SearchCommand.FOCUS_ALBUM
            !artist.isNullOrBlank() && query.isNotBlank() -> SearchCommand.FOCUS_TITLE
            !artist.isNullOrBlank() -> SearchCommand.FOCUS_ARTIST
            !genre.isNullOrBlank() -> SearchCommand.FOCUS_GENRE
            else -> null
        }
        if (focus == null) {
            // Plain unstructured search: flat query only, no extras — exactly
            // what a player that never reads extras expects.
            return SearchCommand(query = flatQuery(), focus = null, extras = emptyMap())
        }
        val extras = mutableMapOf<String, String>()
        fun put(key: String, v: String?) {
            if (!v.isNullOrBlank()) extras[key] = v.trim()
        }
        put(SearchCommand.EXTRA_ARTIST, artist)
        put(SearchCommand.EXTRA_ALBUM, album)
        put(SearchCommand.EXTRA_PLAYLIST, playlist)
        put(SearchCommand.EXTRA_GENRE, genre)
        if (query.isNotBlank()) extras[SearchCommand.EXTRA_TITLE] = query.trim()
        return SearchCommand(query = flatQuery(), focus = focus, extras = extras)
    }

    companion object {
        /** All slots blank-scrubbed; returns null when nothing usable remains. */
        fun clean(
            rawQuery: String,
            artist: String? = null,
            album: String? = null,
            playlist: String? = null,
            genre: String? = null,
            maxQueryLength: Int = 200,
            maxSlotLength: Int = 100,
        ): VoiceQuery? {
            fun cleanSlot(s: String?): String? =
                s?.trim()?.replace(Regex("\\s+"), " ")?.take(maxSlotLength)
                    ?.takeIf { it.isNotBlank() }
            val q = rawQuery.trim().replace(Regex("\\s+"), " ")
                .take(maxQueryLength)
                .takeIf { it.isNotBlank() } ?: ""
            val vq = VoiceQuery(
                query = q,
                artist = cleanSlot(artist),
                album = cleanSlot(album),
                playlist = cleanSlot(playlist),
                genre = cleanSlot(genre),
            )
            return if (vq.isEmpty) null else vq
        }
    }
}

/**
 * A playFromSearch command as pure data. The Android adapter turns this into
 * the extras Bundle (MediaStore.EXTRA_MEDIA_FOCUS + slot extras); the legacy
 * MEDIA_PLAY_FROM_SEARCH intent reuses the same extras with SearchManager.QUERY.
 */
data class SearchCommand(
    /** Flat query text (all non-blank request fields joined; "" only in
     * pure-empty commands like the resume probe). */
    val query: String,
    /** Entry-type focus; one of the FOCUS_* wire constants below, or null
     * for an unstructured search (no extras). */
    val focus: String?,
    /** Slot extras (MediaStore extra keys below → values). */
    val extras: Map<String, String>,
) {
    /** True when no focus/extras exist — the adapter then sends the plain
     * playFromSearch(query, null) form players expect for free-text search. */
    val isUnstructured: Boolean get() = focus == null && extras.isEmpty()

    /**
     * Tier 1 (S4): extras for the legacy MEDIA_PLAY_FROM_SEARCH activity
     * intent — SearchManager.QUERY (the flat text) plus the same structured
     * slot extras when present. Pure so the exact key set is JVM-tested.
     */
    fun toLegacyIntentExtras(): Map<String, String> {
        val out = extras.toMutableMap()
        focus?.let { out[EXTRA_FOCUS] = it }
        out[EXTRA_QUERY] = query
        return out
    }

    companion object {
        // Stable literal values of the MediaStore / Intent string constants
        // (verified against android-all API 30) — re-declared so this layer
        // stays JVM-pure.
        const val EXTRA_FOCUS = "android.intent.extra.focus"
        const val EXTRA_ARTIST = "android.intent.extra.artist"
        const val EXTRA_ALBUM = "android.intent.extra.album"
        const val EXTRA_PLAYLIST = "android.intent.extra.playlist"
        const val EXTRA_GENRE = "android.intent.extra.genre"
        const val EXTRA_TITLE = "android.intent.extra.title"

        /** MediaStore.Audio.* ENTRY_CONTENT_TYPE values — the actual focus
         * wire values of the Assistant contract (NOT plain "artist" etc.). */
        const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
        const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
        const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"
        const val FOCUS_GENRE = "vnd.android.cursor.item/genre"
        /** Title-focused (song) search: TITLE (+ ARTIST) extras. */
        const val FOCUS_TITLE = "vnd.android.cursor.item/audio"

        /** android.app.SearchManager.QUERY */
        const val EXTRA_QUERY = "query"
    }
}

/**
 * Verification scoring (audit M3, full fix).
 *
 * normalize = lowercase + strip punctuation + collapse whitespace, so
 * «AC/DC», «AC-DC» and «AC DC» compare equal and punctuation-heavy player
 * titles ("Bohemian Rhapsody - 2011 Remaster") do not sink the score.
 *
 * Each present expectation contributes a weighted component; the score is
 * the weighted mean of intersection-over-requested-token overlaps. A request
 * with NO matchable expectation scores 0 (no evidence) — it must verify by
 * other means (e.g. the empty-query resume path), never by silently passing.
 */
object VoiceQueryMatcher {

    fun normalize(s: String?): String =
        (s ?: "")
            .lowercase()
            .replace(Regex("[\\p{Punct}«»„“”…—–′′'`]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(s: String?): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    /** |tokens(actual) ∩ requested| / |requested| — 0 when nothing requested. */
    fun overlap(actual: String?, requested: Collection<String>): Double {
        if (requested.isEmpty()) return 0.0
        val a = tokens(actual).toSet()
        if (a.isEmpty()) return 0.0
        val hit = requested.count { it in a }
        return hit.toDouble() / requested.size
    }

    /** Combined match score of [np] against the request, 0.0 … 1.0. */
    fun score(np: NowPlaying, vq: VoiceQuery): Double {
        var weight = 0.0
        var acc = 0.0

        val qTokens = tokens(vq.query)
        if (qTokens.isNotEmpty()) {
            weight += W_TITLE
            acc += W_TITLE * overlap(np.title, qTokens)
        }
        vq.artist?.let { s ->
            val t = tokens(s)
            if (t.isNotEmpty()) {
                weight += W_ARTIST
                acc += W_ARTIST * overlap(np.artist, t)
            }
        }
        vq.album?.let { s ->
            val t = tokens(s)
            if (t.isNotEmpty()) {
                weight += W_ALBUM
                acc += W_ALBUM * overlap(np.album, t)
            }
        }
        vq.genre?.let { s ->
            val t = tokens(s)
            if (t.isNotEmpty()) {
                weight += W_GENRE
                acc += W_GENRE * overlap(np.genre, t)
            }
        }
        // A playlist name is not reflected in track metadata — nothing to score.
        if (weight == 0.0) return 0.0
        return acc / weight
    }

    /**
     * Weak evidence: at least one requested token appears in the matching
     * metadata field. Used only together with a position reset.
     */
    fun partialMatch(np: NowPlaying, vq: VoiceQuery): Boolean {
        val q = tokens(vq.query)
        if (q.isNotEmpty() && overlap(np.title, q) > 0) return true
        vq.artist?.let { if (overlap(np.artist, tokens(it)) > 0) return true }
        vq.album?.let { if (overlap(np.album, tokens(it)) > 0) return true }
        vq.genre?.let { if (overlap(np.genre, tokens(it)) > 0) return true }
        return false
    }

    /**
     * The strong rule: the now-playing track matches the request well enough
     * to speak «Включил …» — OR the position reset while something partially
     * matches (same song restarted / re-searched, which titles alone miss).
     */
    fun isVerified(
        np: NowPlaying,
        vq: VoiceQuery,
        before: NowPlaying?,
        strongThreshold: Double = STRONG_THRESHOLD,
    ): Boolean {
        if (!np.isPlaying) return false
        if (score(np, vq) >= strongThreshold) return true
        if (before != null && np.positionMs < before.positionMs && partialMatch(np, vq)) return true
        return false
    }

    const val STRONG_THRESHOLD = 0.5

    /**
     * True when the request carries at least one expectation [score] can
     * test (title/artist/album/genre tokens). A playlist-only request never
     * appears in track metadata: score is always 0 for it, so the caller
     * must verify by other means (state evidence), never by silently
     * passing — and never by silently failing a compliant player either.
     */
    fun hasScoreableExpectation(vq: VoiceQuery): Boolean =
        vq.query.isNotBlank() ||
            !vq.artist.isNullOrBlank() ||
            !vq.album.isNullOrBlank() ||
            !vq.genre.isNullOrBlank()

    private const val W_TITLE = 0.65
    private const val W_ARTIST = 0.35
    private const val W_ALBUM = 0.35
    private const val W_GENRE = 0.25
}
