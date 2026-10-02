package com.jarvis.assistant.tools

/**
 * Canonical name → [ToolRisk] table for the 25-tool production surface.
 *
 * This is the ONE testable place the classification is declared. It exists
 * because the abstract [ToolContract.risk] property alone is only a
 * compile-time presence check: a tool could be silently reclassified to
 * READ_ONLY and every test would still pass. Two nets close that:
 *
 *  - [ToolRegistry] cross-checks every registered tool against this table and
 *    fails loudly on a mismatch (`Tool 'x' declares A but ToolRisks says B`);
 *  - `ToolAuthorizationTest` pins these VALUES and verifies each production
 *    tool class's COMPILED `risk` against the table (bytecode constant-pool
 *    scan), so a reclassification in either direction fails the suite even
 *    for Android-bound tools that cannot be instantiated in a JVM test.
 *
 * The three cognitive memory tools are listed here too (their declarations
 * live in `cognitive/tools/MemoryTools.kt`, which this table must agree with).
 */
object ToolRisks {

    val byName: Map<String, ToolRisk> = mapOf(
        // alarms / timers
        "setAlarm" to ToolRisk.STATEFUL,
        "cancelAlarm" to ToolRisk.IRREVERSIBLE,
        "listAlarms" to ToolRisk.READ_ONLY,
        "setTimer" to ToolRisk.STATEFUL,
        "cancelTimer" to ToolRisk.IRREVERSIBLE,
        // weather + geo (network egress, no local mutation)
        "getWeather" to ToolRisk.READ_ONLY,
        "findPlace" to ToolRisk.READ_ONLY,
        "getRoute" to ToolRisk.READ_ONLY,
        "getCurrentLocation" to ToolRisk.READ_ONLY,
        // device control
        "setVolume" to ToolRisk.STATEFUL,
        "setBrightness" to ToolRisk.STATEFUL,
        "setWifi" to ToolRisk.STATEFUL,
        "setBluetooth" to ToolRisk.STATEFUL,
        "setDnd" to ToolRisk.STATEFUL,
        "lockScreen" to ToolRisk.STATEFUL,
        "openApp" to ToolRisk.STATEFUL,
        "getDeviceInfo" to ToolRisk.READ_ONLY,
        // music
        "playMusic" to ToolRisk.STATEFUL,
        "controlPlayback" to ToolRisk.STATEFUL,
        "getNowPlaying" to ToolRisk.READ_ONLY,
        "listPlaylists" to ToolRisk.READ_ONLY,
        "searchLibrary" to ToolRisk.READ_ONLY,
        // cognitive memory
        "remember_fact" to ToolRisk.STATEFUL,
        "recall_facts" to ToolRisk.READ_ONLY,
        "forget_fact" to ToolRisk.IRREVERSIBLE,
    )

    /** The declared risk for [name]; throws for an unknown tool name. */
    fun of(name: String): ToolRisk = byName.getValue(name)
}
