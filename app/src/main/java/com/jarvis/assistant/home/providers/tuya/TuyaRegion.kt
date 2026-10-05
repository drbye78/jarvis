package com.jarvis.assistant.home.providers.tuya

/**
 * Tuya data centers. A Cloud Project is created in ONE data center, and it can
 * only see devices whose owning Smart Life account lives in the same DC —
 * devices in another DC simply do not appear. The choice is therefore driven by
 * the user's ACCOUNT REGION, not by preference.
 *
 * The [baseUrl] is the OpenAPI host for that DC. These are compile-time
 * constants (never user-supplied), so there is no SSRF surface from a URL here;
 * the user only picks a region.
 *
 * Persisted form is [id] (never the enum name), mirroring [HomeProviderId], so
 * renaming a constant cannot orphan a stored configuration.
 */
enum class TuyaRegion(val id: String, val baseUrl: String) {
    CHINA("china", "https://openapi.tuyacn.com"),
    WESTERN_AMERICA("us", "https://openapi.tuyaus.com"),
    EASTERN_AMERICA_AZURE("us_az", "https://openapi-ueaz.tuyaus.com"),
    CENTRAL_EUROPE("eu", "https://openapi.tuyaeu.com"),
    WESTERN_EUROPE_AZURE("eu_az", "https://openapi-weaz.tuyaeu.com"),
    INDIA("in", "https://openapi.tuyain.com"),
    SINGAPORE("sg", "https://openapi-sg.iotbing.com"),
    ;

    companion object {
        /**
         * Tolerant parse: null/blank/unknown → [CENTRAL_EUROPE].
         *
         * Central Europe is the sensible default because it serves the widest
         * set of regions (Europe/Middle East/Africa, including Russia). A wrong
         * guess is not destructive — it fails visibly when no devices appear,
         * and the user re-points the region.
         */
        fun fromId(raw: String?): TuyaRegion =
            entries.firstOrNull { it.id == raw?.trim()?.lowercase() } ?: CENTRAL_EUROPE
    }
}
