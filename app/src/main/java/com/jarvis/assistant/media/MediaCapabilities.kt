package com.jarvis.assistant.media

/**
 * Tier 0: what a media session actually supports, decoded from its
 * PlaybackState action mask. THE core principle of the capability lane:
 * vendor docs are hints; `PlaybackState.getActions()` is ground truth.
 *
 * The mask is a plain Long — framework sessions publish the API-21..24 bits,
 * androidx MediaSessionCompat sessions additionally publish the compat bits
 * (repeat/shuffle/speed/captioning) in the SAME field, because compat merely
 * ORs them into the framework PlaybackState. One decoder covers both.
 *
 * Pure Kotlin (constants re-declared, not referenced from the SDK) so every
 * parsing rule is JVM-unit-tested; the Android adapter feeds it the real
 * mask via [MediaControllerHandle.capabilities].
 */
data class MediaCapabilities(
    /** Decoded action bits. Empty when [known] is false. */
    val supported: Set<TransportAction>,
    /** android.media.Rating rating type (RATING_NONE / HEART / 3-5 stars…). */
    val ratingType: Int,
    /** Whether the session exposes a play queue. */
    val hasQueue: Boolean,
    /** Raw mask for diagnostics. */
    val mask: Long,
    /**
     * False when no action bits were set at all — a session that has not
     * published a PlaybackState yet. Gating FAILS OPEN in that case
     * ([supports] returns true): a missing probe must not disable a feature;
     * the verification backstop catches a player that ignores the command.
     */
    val known: Boolean,
) {
    /** Permissive when unknown; strict when the mask was real. */
    fun supports(action: TransportAction): Boolean = !known || action in supported

    fun describe(): String {
        if (!known) return "unknown(no playback state)"
        val bits = supported.joinToString(" ") { it.wireName }
        return "mask=0x${mask.toString(16)} [$bits] rating=${ratingName(ratingType)} queue=$hasQueue"
    }

    companion object {
        /** Mirror of android.media.session.PlaybackState ACTION_* bits. */
        const val ACTION_STOP = 1L
        const val ACTION_PAUSE = 2L
        const val ACTION_PLAY = 4L
        const val ACTION_REWIND = 8L
        const val ACTION_SKIP_TO_PREVIOUS = 16L
        const val ACTION_SKIP_TO_NEXT = 32L
        const val ACTION_FAST_FORWARD = 64L
        const val ACTION_SET_RATING = 128L
        const val ACTION_SEEK_TO = 256L
        const val ACTION_PLAY_PAUSE = 512L
        const val ACTION_PLAY_FROM_MEDIA_ID = 1024L
        const val ACTION_PLAY_FROM_SEARCH = 2048L
        const val ACTION_SKIP_TO_QUEUE_ITEM = 4096L
        const val ACTION_PLAY_FROM_URI = 8192L
        const val ACTION_PREPARE = 16_384L
        const val ACTION_PREPARE_FROM_MEDIA_ID = 32_768L
        const val ACTION_PREPARE_FROM_SEARCH = 65_536L
        const val ACTION_PREPARE_FROM_URI = 131_072L

        /** Compat-only bits (PlaybackStateCompat) — absent from the framework
         *  TransportControls API but published in the same mask field. */
        const val ACTION_SET_REPEAT_MODE = 262_144L
        const val ACTION_SET_SHUFFLE_MODE_ENABLED = 524_288L
        const val ACTION_SET_CAPTIONING_ENABLED = 1_048_576L
        const val ACTION_SET_SHUFFLE_MODE = 2_097_152L
        const val ACTION_SET_PLAYBACK_SPEED = 4_194_304L

        /** android.media.Rating constants relevant to us. */
        const val RATING_NONE = 0
        const val RATING_HEART = 1
        const val RATING_THUMB_UP_DOWN = 2
        const val RATING_3_STARS = 3
        const val RATING_4_STARS = 4
        const val RATING_5_STARS = 5
        const val RATING_PERCENTAGE = 6

        /**
         * PlaybackStateCompat.REPEAT_MODE_* / SHUFFLE_MODE_* mirrors — the
         * wire values of the compat setRepeatMode/setShuffleMode protocol
         * (they are NOT framework TransportControls actions; the bits above
         * gate them).
         */
        const val REPEAT_MODE_INVALID = -1
        const val REPEAT_MODE_NONE = 0
        const val REPEAT_MODE_ONE = 1
        const val REPEAT_MODE_ALL = 2
        const val REPEAT_MODE_GROUP = 3
        const val SHUFFLE_MODE_INVALID = -1
        const val SHUFFLE_MODE_NONE = 0
        const val SHUFFLE_MODE_ALL = 1
        const val SHUFFLE_MODE_GROUP = 3

        /** LLM/JSON-friendly repeat mode name; unknown → "?". */
        fun repeatModeName(mode: Int): String = when (mode) {
            REPEAT_MODE_NONE -> "off"
            REPEAT_MODE_ONE -> "one"
            REPEAT_MODE_ALL -> "all"
            REPEAT_MODE_GROUP -> "group"
            else -> "?"
        }

        /** LLM/JSON-friendly shuffle state; unknown → null. */
        fun shuffleEnabled(mode: Int): Boolean? = when (mode) {
            SHUFFLE_MODE_NONE -> false
            SHUFFLE_MODE_ALL, SHUFFLE_MODE_GROUP -> true
            else -> null
        }

        /** A session with no published PlaybackState — fail open. */
        val UNKNOWN = MediaCapabilities(
            supported = emptySet(),
            ratingType = RATING_NONE,
            hasQueue = false,
            mask = 0L,
            known = false,
        )

        private val BITS = listOf(
            ACTION_STOP to TransportAction.STOP,
            ACTION_PAUSE to TransportAction.PAUSE,
            ACTION_PLAY to TransportAction.PLAY,
            ACTION_REWIND to TransportAction.REWIND,
            ACTION_SKIP_TO_PREVIOUS to TransportAction.SKIP_TO_PREVIOUS,
            ACTION_SKIP_TO_NEXT to TransportAction.SKIP_TO_NEXT,
            ACTION_FAST_FORWARD to TransportAction.FAST_FORWARD,
            ACTION_SET_RATING to TransportAction.SET_RATING,
            ACTION_SEEK_TO to TransportAction.SEEK_TO,
            ACTION_PLAY_PAUSE to TransportAction.PLAY_PAUSE,
            ACTION_PLAY_FROM_MEDIA_ID to TransportAction.PLAY_FROM_MEDIA_ID,
            ACTION_PLAY_FROM_SEARCH to TransportAction.PLAY_FROM_SEARCH,
            ACTION_SKIP_TO_QUEUE_ITEM to TransportAction.SKIP_TO_QUEUE_ITEM,
            ACTION_PLAY_FROM_URI to TransportAction.PLAY_FROM_URI,
            ACTION_PREPARE to TransportAction.PREPARE,
            ACTION_PREPARE_FROM_MEDIA_ID to TransportAction.PREPARE_FROM_MEDIA_ID,
            ACTION_PREPARE_FROM_SEARCH to TransportAction.PREPARE_FROM_SEARCH,
            ACTION_PREPARE_FROM_URI to TransportAction.PREPARE_FROM_URI,
            ACTION_SET_REPEAT_MODE to TransportAction.SET_REPEAT_MODE,
            ACTION_SET_SHUFFLE_MODE to TransportAction.SET_SHUFFLE_MODE,
            ACTION_SET_PLAYBACK_SPEED to TransportAction.SET_PLAYBACK_SPEED,
        )

        /**
         * @param mask PlaybackState.getActions()
         * @param ratingType MediaController.getRatingType()
         * @param hasQueue MediaController.getQueue() != null
         */
        fun fromActionMask(
            mask: Long,
            ratingType: Int = RATING_NONE,
            hasQueue: Boolean = false,
        ): MediaCapabilities {
            if (mask == 0L) return UNKNOWN.copy(mask = 0L, ratingType = ratingType, hasQueue = hasQueue)
            val supported = BITS.filter { (bit, _) -> mask and bit != 0L }
                .map { (_, action) -> action }
                .toSet()
            // Unknown bits (future framework versions, private custom actions)
            // are intentionally ignored — we only gate on actions we can send.
            return MediaCapabilities(
                supported = supported,
                ratingType = ratingType,
                hasQueue = hasQueue,
                mask = mask,
                known = true,
            )
        }

        fun ratingName(type: Int): String = when (type) {
            RATING_NONE -> "none"
            RATING_HEART -> "heart"
            RATING_THUMB_UP_DOWN -> "thumbs"
            RATING_3_STARS -> "3stars"
            RATING_4_STARS -> "4stars"
            RATING_5_STARS -> "5stars"
            RATING_PERCENTAGE -> "percent"
            else -> "type$type"
        }
    }
}

/**
 * Transport commands we can map onto session capabilities. [wireName] is the
 * PlaybackState constant name — used in diagnostics so a logcat line can be
 * diffed against the framework docs.
 */
enum class TransportAction(val wireName: String) {
    STOP("stop"),
    PAUSE("pause"),
    PLAY("play"),
    REWIND("rewind"),
    SKIP_TO_PREVIOUS("skipPrev"),
    SKIP_TO_NEXT("skipNext"),
    FAST_FORWARD("fastForward"),
    SET_RATING("rating"),
    SEEK_TO("seekTo"),
    PLAY_PAUSE("playPause"),
    PLAY_FROM_MEDIA_ID("playFromMediaId"),
    PLAY_FROM_SEARCH("playFromSearch"),
    SKIP_TO_QUEUE_ITEM("skipToQueueItem"),
    PLAY_FROM_URI("playFromUri"),
    PREPARE("prepare"),
    PREPARE_FROM_MEDIA_ID("prepareFromMediaId"),
    PREPARE_FROM_SEARCH("prepareFromSearch"),
    PREPARE_FROM_URI("prepareFromUri"),
    SET_REPEAT_MODE("repeat"),
    SET_SHUFFLE_MODE("shuffle"),
    SET_PLAYBACK_SPEED("speed"),
}
