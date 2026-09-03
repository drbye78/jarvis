package com.jarvis.assistant

import com.jarvis.assistant.session.TurnActivity
import com.jarvis.assistant.session.TurnActivityLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** G3: every registered tool name resolves to a pill label; unknowns fall back. */
class TurnActivityLabelsTest {

    /** The full FunctionRouter registry surface at the time of writing. */
    private val knownTools = listOf(
        "setAlarm", "cancelAlarm", "listAlarms", "setTimer", "cancelTimer",
        "getWeather", "getDeviceInfo", "setBrightness", "setVolume", "setWifi",
        "setBluetooth", "setDnd", "lockScreen", "openApp", "playMusic",
        "controlPlayback", "getNowPlaying", "listPlaylists", "searchLibrary",
    )

    @Test
    fun `every known tool maps to a dedicated label`() {
        knownTools.forEach { tool ->
            assertNotNull("no label for tool $tool", TurnActivityLabels.toolRes(tool))
        }
    }

    @Test
    fun `unknown tool falls back to the generic label`() {
        assertNull(TurnActivityLabels.toolRes("futureToolXYZ"))
        assertEquals(
            R.string.activity_tool_unknown,
            TurnActivityLabels.labelRes(TurnActivity.ToolRunning("futureToolXYZ")),
        )
    }

    @Test
    fun `thinking reuses the state label`() {
        assertEquals(R.string.state_thinking_full, TurnActivityLabels.labelRes(TurnActivity.Thinking))
    }
}
