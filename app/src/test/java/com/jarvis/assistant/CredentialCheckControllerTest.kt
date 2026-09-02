package com.jarvis.assistant

import com.jarvis.assistant.llm.CredentialCheck
import com.jarvis.assistant.llm.CredentialCheckController
import com.jarvis.assistant.llm.CredentialCheckController.Service
import com.jarvis.assistant.llm.CredentialCheckController.UiState
import com.jarvis.assistant.llm.CredentialValidator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Virtual-time tests for the Settings credential validation state machine:
 * debounce coalescing, blank guards, verdict mapping, stale-verdict discard,
 * confirmed-Ok dedup, immediate checkNow, service isolation.
 *
 * The controller runs in a SIBLING scope (test scheduler dispatcher + a fresh
 * root [SupervisorJob]): runTest's own job must not wait for the infinite
 * debounce collectors, and backgroundScope's suspended timers are not resumed
 * by virtual-time advancement in coroutines-test 1.7.3 (verified by a probe —
 * see scripts/probe/DebounceRepro.kt). The sibling is cancelled in [tearDown].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialCheckControllerTest {

    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        scope?.cancel()
        scope = null
    }

    private fun TestScope.newController(
        fake: CredentialValidator,
        debounceMs: Long = 900L,
    ): CredentialCheckController {
        val s = CoroutineScope(coroutineContext + SupervisorJob())
        scope = s
        return CredentialCheckController(fake, s, debounceMs)
    }

    /** Scriptable validator: records calls, can hold the first Salute probe. */
    private class FakeValidator : CredentialValidator {
        val saluteCalls = mutableListOf<Pair<String, String>>()
        val gigachatCalls = mutableListOf<Pair<String, String>>()
        var result: CredentialCheck = CredentialCheck.Valid

        /** When set, the first checkSalute suspends until completed. */
        var holdFirstSalute: CompletableDeferred<Unit>? = null
        private var held = false

        override suspend fun checkSalute(clientId: String, clientSecret: String): CredentialCheck {
            saluteCalls += clientId to clientSecret
            val gate = holdFirstSalute
            if (gate != null && !held) {
                held = true
                gate.await()
            }
            return result
        }

        override suspend fun checkGigaChat(clientId: String, clientSecret: String): CredentialCheck {
            gigachatCalls += clientId to clientSecret
            return result
        }
    }

    @Test
    fun `rapid typing coalesces into one probe with the latest values`() = runTest {
        val fake = FakeValidator()
        val controller = newController(fake, debounceMs = 900)

        controller.onSaluteInput("id1", "sec1")
        advanceTimeBy(400)
        controller.onSaluteInput("id1x", "sec1")
        advanceTimeBy(400)
        controller.onSaluteInput("id1x", "sec1y")
        advanceTimeBy(900)
        advanceUntilIdle()

        assertEquals(1, fake.saluteCalls.size)
        assertEquals("id1x" to "sec1y", fake.saluteCalls.single())
        assertEquals(UiState.Verdict(CredentialCheck.Valid), controller.states.value[Service.SALUTE])
    }

    @Test
    fun `blank pair never probes and resets a prior verdict to idle`() = runTest {
        val fake = FakeValidator()
        val controller = newController(fake, debounceMs = 900)

        controller.onSaluteInput("a", "b")
        advanceUntilIdle()
        assertEquals(UiState.Verdict(CredentialCheck.Valid), controller.states.value[Service.SALUTE])

        // One side blanked: no probe, verdict cleared.
        controller.onSaluteInput("a", "")
        advanceUntilIdle()
        assertEquals(UiState.Idle, controller.states.value[Service.SALUTE])
        assertEquals(1, fake.saluteCalls.size)

        // Blank from the very start: never probed at all.
        controller.onGigaChatInput("", "x")
        advanceUntilIdle()
        assertEquals(0, fake.gigachatCalls.size)
        assertEquals(UiState.Idle, controller.states.value[Service.GIGACHAT])
    }

    @Test
    fun `verdicts map one-to-one onto UI state`() = runTest {
        val fake = FakeValidator()
        val controller = newController(fake, debounceMs = 10)

        fake.result = CredentialCheck.Invalid(401)
        controller.onSaluteInput("a", "b")
        advanceUntilIdle()
        assertEquals(UiState.Verdict(CredentialCheck.Invalid(401)), controller.states.value[Service.SALUTE])

        fake.result = CredentialCheck.Unverifiable("ConnectException")
        controller.onSaluteInput("a2", "b2")
        advanceUntilIdle()
        assertEquals(
            UiState.Verdict(CredentialCheck.Unverifiable("ConnectException")),
            controller.states.value[Service.SALUTE]
        )
    }

    @Test
    fun `stale verdict is discarded when input changed during the probe`() = runTest {
        val fake = FakeValidator().apply {
            holdFirstSalute = CompletableDeferred()
            // The STALE probe would say Invalid; the probe for the new values
            // says Valid — distinguishable outcomes.
            result = CredentialCheck.Invalid(401)
        }
        val controller = newController(fake, debounceMs = 900)

        controller.onSaluteInput("a", "b")
        // +1: advanceTimeBy's window is exclusive of the exact boundary task.
        advanceTimeBy(901) // probe starts and holds
        assertEquals(UiState.Checking, controller.states.value[Service.SALUTE])

        // User keeps typing while the probe is in flight.
        controller.onSaluteInput("a", "c")
        // Release the stale probe: its verdict must NOT appear.
        fake.holdFirstSalute!!.complete(Unit)
        runCurrent()
        assertEquals(UiState.Checking, controller.states.value[Service.SALUTE])

        // The newer values get their own probe (Invalid per script).
        fake.result = CredentialCheck.Invalid(401)
        advanceUntilIdle()
        assertEquals(UiState.Verdict(CredentialCheck.Invalid(401)), controller.states.value[Service.SALUTE])
        assertEquals(listOf("a" to "b", "a" to "c"), fake.saluteCalls)
    }

    @Test
    fun `checkNow probes immediately without waiting for the debounce`() = runTest {
        val fake = FakeValidator()
        val controller = newController(fake, debounceMs = 60_000)

        controller.onSaluteInput("a", "b")
        controller.onGigaChatInput("c", "d")
        controller.checkNow()
        runCurrent() // no virtual time advanced — debounce would never fire

        assertEquals(1, fake.saluteCalls.size)
        assertEquals(1, fake.gigachatCalls.size)
        assertEquals(UiState.Verdict(CredentialCheck.Valid), controller.states.value[Service.SALUTE])
        assertEquals(UiState.Verdict(CredentialCheck.Valid), controller.states.value[Service.GIGACHAT])
    }

    @Test
    fun `confirmed valid pair is not re-probed by debounce but checkNow forces it`() = runTest {
        val fake = FakeValidator()
        val controller = newController(fake, debounceMs = 10)

        controller.onSaluteInput("a", "b")
        advanceUntilIdle()
        assertEquals(1, fake.saluteCalls.size)

        // Leave the fields, come back to the same values: no new probe.
        controller.onSaluteInput("x", "y")
        advanceTimeBy(5)
        controller.onSaluteInput("a", "b")
        advanceUntilIdle()
        assertEquals(1, fake.saluteCalls.size)

        // The button means "ask again": forced re-probe.
        controller.checkNow()
        advanceUntilIdle()
        assertEquals(2, fake.saluteCalls.size)
    }

    @Test
    fun `services are independent - salute input never probes gigachat`() = runTest {
        val fake = FakeValidator()
        val controller = newController(fake, debounceMs = 10)

        controller.onSaluteInput("a", "b")
        advanceUntilIdle()

        assertEquals(1, fake.saluteCalls.size)
        assertEquals(0, fake.gigachatCalls.size)
        assertEquals(UiState.Idle, controller.states.value[Service.GIGACHAT])
    }
}
