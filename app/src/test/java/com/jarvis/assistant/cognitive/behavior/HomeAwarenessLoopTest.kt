package com.jarvis.assistant.cognitive.behavior

import com.jarvis.assistant.cognitive.data.BehaviorLogEntity
import com.jarvis.assistant.cognitive.extract.FakeBehaviorLogDao
import com.jarvis.assistant.home.Capability
import com.jarvis.assistant.home.DeviceKind
import com.jarvis.assistant.home.HomeDeviceKey
import com.jarvis.assistant.home.HomeEntityCodec
import com.jarvis.assistant.home.HomeEventSource
import com.jarvis.assistant.home.HomeProviderId
import com.jarvis.assistant.home.HomeState
import com.jarvis.assistant.home.HomeStateChange
import com.jarvis.assistant.home.HomeValue
import com.jarvis.assistant.tools.ToolStrings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R4/H4: the awareness loop's integration contract — opt-in gating, content-free
 * delivery, debounce, and CRUCIALLY that a home notice never contaminates the
 * habit lane's `FIRED` accounting (daily quota + reject path).
 */
class HomeAwarenessLoopTest {

    private val key = HomeDeviceKey(HomeProviderId.HOME_ASSISTANT, "appliance.washer")

    private fun state(chef: Boolean, extraAttributes: Map<String, String> = emptyMap()) = HomeState(
        key = key,
        values = mapOf(Capability.ON_OFF to HomeValue.Bool(chef)),
        raw = extraAttributes,
        atMs = 0,
    )

    private fun change(
        kind: DeviceKind = DeviceKind.APPLIANCE_COOKING,
        old: Boolean,
        new: Boolean,
        atMs: Long,
        extraAttributes: Map<String, String> = emptyMap(),
    ) = HomeStateChange(
        key = key,
        kind = kind,
        old = state(old, extraAttributes),
        new = state(new, extraAttributes),
        atMs = atMs,
    )

    private class FakeEventSource : HomeEventSource {
        override val provider = HomeProviderId.HOME_ASSISTANT
        val flow = MutableSharedFlow<HomeStateChange>(extraBufferCapacity = 32)
        var subscribeCount = 0
        var lastKeys: Set<HomeDeviceKey> = emptySet()

        override fun events(keys: Set<HomeDeviceKey>): Flow<HomeStateChange> {
            subscribeCount++
            lastKeys = keys
            return flow
        }
    }

    private fun loop(
        scope: kotlinx.coroutines.test.TestScope,
        source: FakeEventSource,
        speaker: MutableList<String>,
        log: FakeBehaviorLogDao,
        enabled: Boolean,
        curated: Set<HomeDeviceKey>,
        nowMs: () -> Long = { 1_000L },
        idle: MutableStateFlow<Boolean> = MutableStateFlow(true),
        dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
    ) = HomeAwarenessLoop(
        // backgroundScope: runTest cancels it automatically, so the infinite
        // collector does not leave the test waiting for a child to finish.
        scope = scope.backgroundScope,
        eventSource = { source },
        awarenessEnabled = MutableStateFlow(enabled),
        curated = MutableStateFlow(HomeEntityCodec.encode(curated)),
        logDao = log,
        speaker = ProactiveSpeaker { text ->
            speaker += text
            true
        },
        strings = ToolStrings.Default,
        signals = DeviceSignals.Static,
        sessionIdle = idle,
        lastInteractionAt = { nowMs() },
        quietStart = MutableStateFlow(23),
        quietEnd = MutableStateFlow(8),
        dailyCap = MutableStateFlow(2),
        hourOfDay = { 12 },
        io = dispatcher,
    )

    @Test
    fun `disabled delivers nothing and opens no subscription`() = runTest {
        val source = FakeEventSource()
        val speaker = mutableListOf<String>()
        val log = FakeBehaviorLogDao()
        val loop = loop(this, source, speaker, log, enabled = false, curated = setOf(key))
        loop.start()
        advanceUntilIdle()
        source.flow.emit(change(old = true, new = false, atMs = 1_000))
        advanceUntilIdle()
        assertTrue(speaker.isEmpty())
        assertEquals(0, log.rows.size)
        assertEquals(0, source.subscribeCount)
    }

    @Test
    fun `enabled but uncurated opens no subscription`() = runTest {
        val source = FakeEventSource()
        val loop = loop(this, source, mutableListOf(), FakeBehaviorLogDao(), enabled = true, curated = emptySet())
        loop.start()
        advanceUntilIdle()
        assertEquals(0, source.subscribeCount)
    }

    @Test
    fun `an appliance completion is spoken and logged as a home notice`() = runTest {
        val source = FakeEventSource()
        val speaker = mutableListOf<String>()
        val log = FakeBehaviorLogDao()
        val loop = loop(this, source, speaker, log, enabled = true, curated = setOf(key))
        loop.start()
        advanceUntilIdle()
        assertEquals(setOf(key), source.lastKeys)

        source.flow.emit(change(old = true, new = false, atMs = 2_000))
        advanceUntilIdle()

        assertEquals(1, speaker.size)
        assertEquals(1, log.rows.size)
        assertEquals(BehaviorLogEntity.DECISION_HOME_NOTICE, log.rows.single().decision)
        assertNull(log.rows.single().ruleId)
    }

    @Test
    fun `the notice is content free - no device name or raw attribute leaks`() = runTest {
        val source = FakeEventSource()
        val speaker = mutableListOf<String>()
        val loop = loop(this, source, speaker, FakeBehaviorLogDao(), enabled = true, curated = setOf(key))
        loop.start()
        advanceUntilIdle()
        source.flow.emit(
            change(
                old = true,
                new = false,
                atMs = 2_000,
                extraAttributes = mapOf("friendly_name" to "Стиральная машина"),
            ),
        )
        advanceUntilIdle()
        assertEquals(1, speaker.size)
        assertTrue(speaker.single().isNotBlank())
        assertTrue("must not speak the device name", "Стиральная" !in speaker.single())
    }

    @Test
    fun `a home notice never contaminates the habit FIRED accounting`() = runTest {
        val source = FakeEventSource()
        val log = FakeBehaviorLogDao()
        val loop = loop(this, source, mutableListOf(), log, enabled = true, curated = setOf(key))
        loop.start()
        advanceUntilIdle()
        source.flow.emit(change(old = true, new = false, atMs = 2_000))
        advanceUntilIdle()

        // The habit quota counts FIRED only; the reject path reads latestFired.
        assertEquals(0, log.firedSince(0))
        assertNull(log.latestFiredSince(0))
        assertNotEquals("FIRED", log.rows.single().decision)
        assertEquals(1, log.homeNoticeSince(0))
    }

    @Test
    fun `a light toggle produces no notice`() = runTest {
        val source = FakeEventSource()
        val speaker = mutableListOf<String>()
        val loop = loop(this, source, speaker, FakeBehaviorLogDao(), enabled = true, curated = setOf(key))
        loop.start()
        advanceUntilIdle()
        source.flow.emit(change(kind = DeviceKind.LIGHT, old = true, new = false, atMs = 2_000))
        advanceUntilIdle()
        assertTrue(speaker.isEmpty())
    }

    @Test
    fun `debounce suppresses a repeated transition inside the cooldown`() = runTest {
        val source = FakeEventSource()
        val speaker = mutableListOf<String>()
        val log = FakeBehaviorLogDao()
        val loop = loop(this, source, speaker, log, enabled = true, curated = setOf(key))
        loop.start()
        advanceUntilIdle()

        source.flow.emit(change(old = true, new = false, atMs = 1_000))
        advanceUntilIdle()
        source.flow.emit(change(old = true, new = false, atMs = 2_000))
        advanceUntilIdle()

        assertEquals("the second notice is inside the 5 s cooldown", 1, speaker.size)
        assertEquals(1, log.rows.size)
    }

    @Test
    fun `a busy session blocks the notice`() = runTest {
        val source = FakeEventSource()
        val speaker = mutableListOf<String>()
        val loop = loop(
            this,
            source,
            speaker,
            FakeBehaviorLogDao(),
            enabled = true,
            curated = setOf(key),
            idle = MutableStateFlow(false),
        )
        loop.start()
        advanceUntilIdle()
        source.flow.emit(change(old = true, new = false, atMs = 2_000))
        advanceUntilIdle()
        assertTrue(speaker.isEmpty())
    }
}
