package com.jarvis.assistant.session

import com.jarvis.assistant.R

/**
 * What the turn engine is doing RIGHT NOW, published while the state machine
 * sits in THINKING. The home screen's status pill previously showed a flat
 * «Думаю…» for the entire THINKING phase — including every tool call — so a
 * 20-second music cascade looked identical to a fast weather lookup.
 *
 * [TurnRunner] emits [Thinking] per LLM pass and [ToolRunning] before each
 * tool execution; [SessionManager] republishes on [turnActivity] and clears
 * it (null) at every terminal. `null` means "no finer granularity — render
 * the generic THINKING label".
 */
sealed interface TurnActivity {
    /** An LLM pass is streaming (thinking, composing the answer). */
    data object Thinking : TurnActivity

    /** A tool call is executing (alarm, weather, music cascade, …). */
    data class ToolRunning(val tool: String) : TurnActivity
}

/**
 * Static mapping tool name → status-label resource. Pure + JVM-testable
 * (R.string constants are compile-time ints). Unknown tools fall back to
 * [R.string.activity_tool_unknown] so a newly added tool degrades gracefully
 * instead of showing an empty pill.
 */
object TurnActivityLabels {

    /** Resource for the pill while THINKING with [TurnActivity.Thinking]. */
    const val THINKING_RES: Int = R.string.state_thinking_full

    fun labelRes(activity: TurnActivity): Int = when (activity) {
        TurnActivity.Thinking -> THINKING_RES
        is TurnActivity.ToolRunning -> toolRes(activity.tool) ?: R.string.activity_tool_unknown
    }

    /** Per-tool label resource, or null when the tool is unknown. */
    fun toolRes(tool: String): Int? = when (tool) {
        "setAlarm" -> R.string.activity_tool_set_alarm
        "cancelAlarm" -> R.string.activity_tool_cancel_alarm
        "listAlarms" -> R.string.activity_tool_list_alarms
        "setTimer" -> R.string.activity_tool_set_timer
        "cancelTimer" -> R.string.activity_tool_cancel_timer
        "getWeather" -> R.string.activity_tool_get_weather
        "getDeviceInfo" -> R.string.activity_tool_get_device_info
        "setBrightness" -> R.string.activity_tool_set_brightness
        "setVolume" -> R.string.activity_tool_set_volume
        "setWifi" -> R.string.activity_tool_set_wifi
        "setBluetooth" -> R.string.activity_tool_set_bluetooth
        "setDnd" -> R.string.activity_tool_set_dnd
        "lockScreen" -> R.string.activity_tool_lock_screen
        "openApp" -> R.string.activity_tool_open_app
        "playMusic" -> R.string.activity_tool_play_music
        "controlPlayback" -> R.string.activity_tool_control_playback
        "getNowPlaying" -> R.string.activity_tool_get_now_playing
        "listPlaylists" -> R.string.activity_tool_list_playlists
        "searchLibrary" -> R.string.activity_tool_search_library
        // COGNITIVE_PLAN 1.5: memory tools get status pills like every tool.
        "remember_fact" -> R.string.activity_tool_remember_fact
        "recall_facts" -> R.string.activity_tool_recall_facts
        "forget_fact" -> R.string.activity_tool_forget_fact
        else -> null
    }
}
