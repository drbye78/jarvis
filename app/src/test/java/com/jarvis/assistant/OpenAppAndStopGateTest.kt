package com.jarvis.assistant

import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.contracts.BargeInPolicy
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.gatedBy
import com.jarvis.assistant.tools.OpenAppOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIXPLAN A5: the openApp honesty decision — a launch from a context with
 * no visible window is an ATTEMPT, not an OK.
 */
class OpenAppOutcomeTest {
    @Test
    fun `visible ui means the launch is bal-permitted`() {
        assertEquals(OpenAppOutcome.OK, OpenAppOutcome.of(uiVisible = true))
    }

    @Test
    fun `no visible window means the launch may be swallowed`() {
        assertEquals(OpenAppOutcome.ATTEMPTED, OpenAppOutcome.of(uiVisible = false))
    }
}

/**
 * FIXPLAN B: the barge-in gate must pass StopPhrase detections UNGATED in
 * every state — one utterance cancels, with no repeat-to-interrupt barrier
 * and no cooldown (state-conditional routing lives in SessionManager).
 */
class StopPhraseGateTest {

    @Test
    fun `stop passes ungated during SPEAKING in repeat mode`() = runTest {
        val state = MutableStateFlow(AssistantState.SPEAKING)
        val policy = BargeInPolicy.from(
            com.jarvis.assistant.config.JarvisConfig(bargeInSingleShot = false),
        )
        val out = listOf(
            Detection.StopPhrase("stop"),
            Detection.WakeWord, // repeat-mode first hit: must stay GATED
        ).asFlow().gatedBy(policy, state).toList()

        assertEquals(1, out.size)
        assertTrue(out[0] is Detection.StopPhrase)
    }

    @Test
    fun `stop passes in IDLE and LISTENING too - routing filters by state later`() = runTest {
        val policy = BargeInPolicy.from(com.jarvis.assistant.config.JarvisConfig())
        for (s in listOf(AssistantState.IDLE, AssistantState.LISTENING, AssistantState.THINKING)) {
            val out = listOf(Detection.StopPhrase("stop"))
                .asFlow().gatedBy(policy, MutableStateFlow(s)).toList()
            assertEquals("state=$s", 1, out.size)
        }
    }

    @Test
    fun `wake repeat-window semantics still hold after a stop passes`() = runTest {
        // The stop path must not have consumed the wake candidate window:
        // a single wake during SPEAKING stays gated.
        val state = MutableStateFlow(AssistantState.SPEAKING)
        val policy = BargeInPolicy.from(
            com.jarvis.assistant.config.JarvisConfig(bargeInSingleShot = false),
        )
        val out = listOf(Detection.WakeWord).asFlow().gatedBy(policy, state).toList()
        assertTrue(out.isEmpty())
    }

    private fun <T> List<T>.asFlow() = kotlinx.coroutines.flow.flow {
        this@asFlow.forEach { emit(it) }
    }
}
