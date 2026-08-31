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
            else -> emptyList()
        }
    }
}
