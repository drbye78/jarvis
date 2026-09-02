package com.jarvis.assistant.media

/**
 * Library browsing — media browser binding, search, and token management.
 *
 * Handles the MediaBrowserService connection lifecycle for browsing a
 * player's library (playlists, search results) and playing specific items
 * by mediaId.
 *
 * Extracted from [MusicPlaybackOrchestrator] (M4 decomposition).
 */
class LibraryBrowser(
    private val browser: MediaBrowserGateway?,
    private val resolver: MusicAppResolver,
    private val budgets: MusicPlaybackOrchestrator.Budgets,
) {

    /** Tier 3: top-level library sections / playlists (root children). */
    suspend fun listPlaylists(appHint: String?): MusicPlaybackOrchestrator.Outcome {
        val app = resolver.resolve(appHint)
            ?: return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR, null,
                detail = "Не нашёл музыкальное приложение на планшете.",
                isError = true,
            )
        val session = connectBrowser(app)
            ?: return browserUnavailable(app)
        try {
            val rootId = session.root()
            if (rootId.isBlank()) {
                return browserUnavailable(app)
            }
            val children = session.children(rootId, budgets.browserSearchTimeoutMs, budgets.maxLibraryItems)
                ?: return browserUnavailable(app)
            if (children.isEmpty()) {
                return MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.DISPATCHED, app, strategy = "browser_children",
                    detail = "В библиотеке ${app.label} ничего не нашлось.",
                )
            }
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED, app, strategy = "browser_children",
                items = children,
                detail = "Вот что есть в библиотеке ${app.label}. Назови название — включу.",
            )
        } finally {
            session.disconnect()
        }
    }

    /** Tier 3: search the player's own library (onSearch results). */
    suspend fun searchLibrary(rawQuery: String, appHint: String?): MusicPlaybackOrchestrator.Outcome {
        val query = rawQuery.trim().replace(Regex("\\s+"), " ").take(budgets.maxQueryLength)
        val app = resolver.resolve(appHint)
            ?: return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR, null,
                detail = "Не нашёл музыкальное приложение на планшете.",
                isError = true,
            )
        if (query.isBlank()) {
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.ERROR, app,
                detail = "Не понял, что искать — назови трек, исполнителя или плейлист.",
                isError = true,
            )
        }
        val session = connectBrowser(app)
            ?: return browserUnavailable(app)
        try {
            val results = session.search(query, budgets.browserSearchTimeoutMs)
                ?: return MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.ERROR, app,
                    detail = "${app.label} не поддерживает поиск по библиотеке. " +
                        "Скажи название трека — я включу его через голосовой поиск.",
                    isError = true,
                )
            val playable = results.filter { it.playable }.take(budgets.maxLibraryItems)
            if (playable.isEmpty()) {
                return MusicPlaybackOrchestrator.Outcome(
                    MusicPlaybackOrchestrator.Status.DISPATCHED, app, strategy = "browser_search",
                    detail = "В библиотеке ничего не нашлось по «$query».",
                )
            }
            return MusicPlaybackOrchestrator.Outcome(
                MusicPlaybackOrchestrator.Status.DISPATCHED, app, strategy = "browser_search",
                items = playable,
                detail = "Вот что нашлось по «$query». Назови номер или название — включу.",
            )
        } finally {
            session.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // Browser connection helpers
    // ------------------------------------------------------------------

    internal suspend fun connectBrowser(app: MediaAppInfo): BrowserSession? {
        val browserGateway = browser ?: return null
        if (browserGateway.discover().none { it.packageName == app.packageName }) return null
        return browserGateway.connect(app.packageName, budgets.browserConnectTimeoutMs)
    }

    internal fun browserUnavailable(app: MediaAppInfo): MusicPlaybackOrchestrator.Outcome =
        MusicPlaybackOrchestrator.Outcome(
            MusicPlaybackOrchestrator.Status.ERROR, app,
            detail = "${app.label} не открывает свою библиотеку для голосового помощника. " +
                "Скажи название трека — я включу его поиском.",
            isError = true,
        )
}
