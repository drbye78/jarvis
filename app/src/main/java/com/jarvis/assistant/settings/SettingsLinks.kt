package com.jarvis.assistant.settings

/**
 * External links the Settings UI opens, kept in ONE place.
 *
 * The Yandex Maps attribution block (WEATHER_MAPS) and the About row (list host)
 * both surface the same legal/entry links. Restating the literals in two classes
 * is how an attribution notice ends up pointing at a stale URL after the terms
 * move — and this one is a licence obligation, not decoration.
 */
object SettingsLinks {

    /** Yandex Maps legal terms — the attribution required by the MapKit licence. */
    const val YANDEX_MAPS_TERMS_URL = "https://yandex.ru/legal/maps_termsofuse"

    /** Yandex Maps entry point (the "open in Maps" attribution action). */
    const val YANDEX_MAPS_URL = "https://yandex.ru/maps"
}
