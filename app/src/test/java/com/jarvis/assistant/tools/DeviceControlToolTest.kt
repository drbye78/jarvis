package com.jarvis.assistant.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlToolTest {
    private val adapter = object : DeviceControlAdapter {
        override suspend fun setState(device: String, state: Boolean): String {
            return """{"status":"ok","device":"$device","state":${if (state) "\"on\"" else "\"off\""}}"""
        }
    }
    private val tool = DeviceControlTool(adapter)

    @Test fun `turn device on`() = runBlocking {
        val result = tool.execute("""{"device":"light","state":"on"}""")
        assertTrue(result.contains("\"status\":\"ok\""))
        assertTrue(result.contains("light"))
    }

    @Test fun `missing device`() = runBlocking {
        val result = tool.execute("""{"state":"on"}""")
        assertTrue(result.contains("\"error\""))
    }
}