package com.jarvis.assistant.tools

import com.jarvis.assistant.data.AlarmEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmToolTest {
    private val fakeScheduler = object : AlarmScheduler {
        var lastLabel = ""
        var lastHour = -1
        var lastMinute = -1
        override fun schedule(label: String, hour: Int, minute: Int): AlarmEntity {
            lastLabel = label; lastHour = hour; lastMinute = minute
            return AlarmEntity(label = label, hour = hour, minute = minute, triggerMillis = 0)
        }
        override fun cancel(label: String, hour: Int, minute: Int) {}
    }

    private val tool = AlarmTool(fakeScheduler)

    @Test fun `valid alarm with label`() = runBlocking {
        val result = tool.execute("""{"time":"07:30","label":"Wake up"}""")
        assertTrue(result.contains("\"status\":\"scheduled\""))
        assertEquals("Wake up", fakeScheduler.lastLabel)
        assertEquals(7, fakeScheduler.lastHour)
        assertEquals(30, fakeScheduler.lastMinute)
    }

    @Test fun `valid alarm without label`() = runBlocking {
        val result = tool.execute("""{"time":"08:00"}""")
        assertTrue(result.contains("\"status\":\"scheduled\""))
        assertEquals("Будильник", fakeScheduler.lastLabel)
    }

    @Test fun `missing time`() = runBlocking {
        val result = tool.execute("""{}""")
        assertTrue(result.contains("\"error\":\"Missing required parameter: time\""))
    }

    @Test fun `invalid hour`() = runBlocking {
        val result = tool.execute("""{"time":"25:00"}""")
        assertTrue(result.contains("\"error\":\"Time out of range\""))
    }

    @Test fun `invalid JSON`() = runBlocking {
        val result = tool.execute("not json")
        assertTrue(result.contains("\"error\":\"Invalid JSON arguments\""))
    }
}