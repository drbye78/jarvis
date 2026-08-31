package com.jarvis.assistant.media

/**
 * Tier 0: capability diagnostics. Formats one logcat-readable line per media
 * session (plus MediaBrowser discovery, once that lane exists) under the
 * MusicDiag tag, so a single «Джарвис, включи музыку» attempt on the tablet
 * answers every ground-truth question no static audit can:
 *
 *  1. does the live session advertise ACTION_PLAY_FROM_SEARCH?
 *  2. repeat/shuffle bits? heart rating? queue?
 *  3. does the browser service accept our package?
 *  4. is onSearch implemented?
 *  5. does an empty query start a queue?
 *
 * RUNBOOK first-line troubleshooting: `adb logcat -s MusicDiag`.
 *
 * Pure string formatting — JVM-tested; the orchestrator logs the output via
 * Timber, which is a no-op in unit tests.
 */
object MediaDiagnostics {

    /** One row of the capability table (snapshot of a live session). */
    data class SessionRow(
        val packageName: String,
        val capabilities: MediaCapabilities,
        val nowPlaying: NowPlaying?,
    )

    fun sessionTable(sessions: List<SessionRow>): List<String> {
        if (sessions.isEmpty()) return listOf("music-diag: no active media sessions")
        val lines = mutableListOf("music-diag: ${sessions.size} active session(s)")
        sessions.forEachIndexed { i, row ->
            lines += sessionLine(i + 1, row)
        }
        return lines
    }

    /**
     * Tier 3: MediaBrowserService discovery — the ground truth for "does the
     * player expose a browser at all" (RUNBOOK question 3). One compact line:
     * the whole list is usually a single service.
     */
    fun browserTable(services: List<BrowserServiceInfo>): List<String> {
        if (services.isEmpty()) {
            return listOf("music-diag: no MediaBrowserService found on device")
        }
        val listed = services.joinToString(", ") { "${it.packageName}(${it.label})" }
        return listOf("music-diag: ${services.size} browser service(s): $listed")
    }

    private fun sessionLine(index: Int, row: SessionRow): String {
        val c = row.capabilities
        fun f(action: TransportAction): Char =
            if (!c.known) '?'
            else if (c.supports(action)) 'Y'
            else '-'

        val flags = listOf(
            TransportAction.PLAY_FROM_SEARCH,
            TransportAction.PLAY_FROM_MEDIA_ID,
            TransportAction.PREPARE_FROM_SEARCH,
            TransportAction.SEEK_TO,
            TransportAction.SKIP_TO_QUEUE_ITEM,
            TransportAction.SET_RATING,
            TransportAction.SET_REPEAT_MODE,
            TransportAction.SET_SHUFFLE_MODE,
            TransportAction.SET_PLAYBACK_SPEED,
        ).joinToString(" ") { "${it.wireName}=${f(it)}" }

        val np = row.nowPlaying?.let { np ->
            val what = listOfNotNull(np.title, np.artist).joinToString(" — ")
            " state=${stateName(np.state)} pos=${np.positionMs / 1000}s" +
                (if (what.isNotBlank()) " «$what»" else "")
        } ?: " state=?"

        return "music-diag: [$index] ${row.packageName} ${c.describe()} $flags$np"
    }

    private fun stateName(state: Int): String = when (state) {
        NowPlaying.STATE_PLAYING -> "PLAYING"
        NowPlaying.STATE_PAUSED -> "PAUSED"
        NowPlaying.STATE_BUFFERING -> "BUFFERING"
        NowPlaying.STATE_STOPPED -> "STOPPED"
        NowPlaying.STATE_NONE -> "NONE"
        else -> "state$state"
    }
}
