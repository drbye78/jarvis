package com.jarvis.assistant.tools

interface DeviceControlAdapter {
    suspend fun setState(device: String, state: Boolean): String
}

class UnconfiguredDeviceControlAdapter : DeviceControlAdapter {
    override suspend fun setState(device: String, state: Boolean): String {
        return """{"error":"Smart-home hub not configured"}"""
    }
}