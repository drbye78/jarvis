package com.jarvis.assistant

import com.jarvis.assistant.settings.ApplyPolicies
import com.jarvis.assistant.settings.ApplyPolicy
import com.jarvis.assistant.util.AppPrefs
import com.jarvis.assistant.util.SecretVault
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the apply-semantics table. A wrong policy is a silently broken user
 * expectation («I changed it and nothing happened»), so every restart-scoped
 * setting and the live defaults are asserted by name.
 */
class ApplyPoliciesTest {

    @Test
    fun `provider and model selections require a service restart`() {
        // These are SEALED at AppGraph construction; only a service restart
        // rebuilds the graph.
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("providerType"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("gigaChatModel"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("yandexModel"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("yandexFolderId"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("openAiBaseUrl"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("openAiModel"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("speechBackend"))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("aecMode"))
    }

    @Test
    fun `the openai key is baked into the client so it needs a service restart`() {
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of("openAiApiKey"))
    }

    @Test
    fun `the mapkit key needs a full app restart not just a service restart`() {
        // MapKitFactory.setApiKey is once-per-PROCESS; the AppGraph (and the
        // initializer) is rebuilt inside the same process on a service restart.
        assertEquals(ApplyPolicy.APP_RESTART, ApplyPolicies.of("mapKitApiKey"))
    }

    @Test
    fun `reactive settings apply live`() {
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of("weatherLocation"))
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of("memoryEnabled"))
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of("ttsVoice"))
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of("yandexApiKey"))
    }

    @Test
    fun `an unknown key defaults to live`() {
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of("notARealSetting"))
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of(""))
    }

    @Test
    fun `the storage-key constant form resolves identically to the property name`() {
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of(AppPrefs.KEY_PROVIDER))
        assertEquals(ApplyPolicy.SERVICE_RESTART, ApplyPolicies.of(AppPrefs.KEY_SPEECH_BACKEND))
        assertEquals(ApplyPolicy.APP_RESTART, ApplyPolicies.of(SecretVault.KEY_MAPKIT_API_KEY))
        assertEquals(ApplyPolicy.LIVE, ApplyPolicies.of(AppPrefs.KEY_WEATHER_LOCATION))
    }
}
