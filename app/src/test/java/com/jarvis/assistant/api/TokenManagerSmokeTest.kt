package com.jarvis.assistant.api

import com.jarvis.assistant.config.JarvisConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TokenManagerSmokeTest {
    @Test fun `config values are read`() {
        val config = JarvisConfig(oauthRefreshThresholdMs = 30_000)
        assertEquals(30_000, config.oauthRefreshThresholdMs)
        assertNotNull(config.oauthEndpoint)
    }

    @Test fun `default config has expected defaults`() {
        val config = JarvisConfig()
        assertEquals(600, config.wakeWordCooldownMs)
        assertEquals(30000, config.llmTimeoutMs)
        assertEquals(60000, config.oauthRefreshThresholdMs)
        assertEquals("https://gigachat.devices.sberbank.ru/api/v1/chat/completions", config.gigaChatEndpoint)
        assertEquals("GigaChat-Pro", config.gigaChatModel)
    }
}
