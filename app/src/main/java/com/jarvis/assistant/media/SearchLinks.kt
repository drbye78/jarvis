package com.jarvis.assistant.media

/**
 * Pure builder for player deep links (M1).
 *
 * Why it exists: the Android adapter used to inline
 * `URLEncoder.encode(query, "UTF-8")`, which encodes a space as `+`. That is
 * correct for HTML form bodies but WRONG in URIs — `Uri.getQueryParameter`
 * never decodes `+` back to a space, and the https path segment form is not
 * decoded at all — so «Кино Группа крови» reached the player as
 * «Кино+Группа+крови» and found nothing. The fix is `Uri.encode` (spaces
 * become `%20`), and the building logic lives here, parameterized by an
 * encoder function, so it is JVM-unit-testable.
 *
 * Per-player honesty: a link is only listed when its SHAPE is confirmed.
 * Zvuk (com.zvooq.openplay) intentionally returns NO links — zvuk.com is
 * geo/bot-blocked from the dev environment, so the web-search URL shape
 * could not be verified, and an unverified link would make the orchestrator
 * claim "search opened" while the user stares at a wrong page. The browser
 * (S0/S2) and session/launch lanes cover Zvuk — it ships official Android
 * Auto support, the strongest MediaBrowserService/playFromSearch signal.
 * RUNBOOK «Zvuk» documents the one-minute on-device check that re-enables
 * a deep-link entry once its shape is confirmed.
 */
object SearchLinks {

    /**
     * Deep-link search URIs for the known players, best first.
     *
     * @param encode a percent-encoder for path/query segments. Production
     *   passes `Uri::encode`; tests pass an identity or fake encoder.
     */
    fun searchUris(packageName: String, query: String, encode: (String) -> String): List<String> {
        val encoded = encode(query)
        return when (packageName) {
            // Yandex Music moved to ru.yandex.music; com.yandex.music is the
            // legacy id some sideloaded APKs still use.
            "ru.yandex.music", "com.yandex.music" -> listOf(
                "yandexmusic://search?query=$encoded",
                "https://music.yandex.ru/search/$encoded",
            )
            // Zvuk: no verified deep link (see class KDoc) — the cascade
            // falls through to launch/legacy strategies.
            "com.zvooq.openplay" -> emptyList()
            else -> emptyList()
        }
    }
}
