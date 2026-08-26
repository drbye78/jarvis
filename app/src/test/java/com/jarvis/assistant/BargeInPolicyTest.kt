package com.jarvis.assistant

import com.jarvis.assistant.config.JarvisConfig
import com.jarvis.assistant.contracts.BargeInPolicy
import com.jarvis.assistant.contracts.Detection
import com.jarvis.assistant.contracts.gatedBy
import com.jarvis.assistant.model.AssistantState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM matrix for the M7 barge-in gate: [AssistantState] × [BargeInPolicy.Mode]
 * × timings, plus DetectorError passthrough and post-accept suppression.
 *
 * Time is fully virtual: the injected clock advances alongside runTest's
 * scheduler so every scenario is deterministic and instant.
 */
class BargeInPolicyTest {

    private data class Step(val advanceMs: Long = 0, val detection: Detection)

    private data class Scenario(
        val name: String,
        val mode: BargeInPolicy.Mode,
        val state: AssistantState,
        val repeatWindowMs: Long = 1_200,
        val steps: List<Step>,
        val expected: List<Detection>,
    )

    private val ww = Detection.WakeWord
    private val err = Detection.DetectorError("engine boom")

    private fun wwAt(ms: Long) = Step(advanceMs = ms, detection = ww)
    private fun errAt(ms: Long) = Step(advanceMs = ms, detection = err)

    private fun scenarios(): List<Scenario> = buildList {
        // Matrix corner 1: outside SPEAKING every single detection passes,
        // mode-irrelevant.
        for (state in AssistantState.entries - AssistantState.SPEAKING) {
            for (mode in BargeInPolicy.Mode.entries) {
                add(
                    Scenario(
                        name = "$state/$mode: first detection passes immediately",
                        mode = mode, state = state,
                        steps = listOf(wwAt(0)),
                        expected = listOf(ww),
                    ),
                )
            }
        }

        // --- SPEAKING × SINGLE ---
        add(
            Scenario(
                "SPEAKING/SINGLE: first detection barges in immediately",
                mode = BargeInPolicy.Mode.SINGLE, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0)),
                expected = listOf(ww),
            ),
        )

        // --- SPEAKING × REPEAT_DURING_PLAYBACK ---
        add(
            Scenario(
                "SPEAKING/REPEAT: second detection inside window passes",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0), wwAt(500)),
                expected = listOf(ww), // only the SECOND one passes
            ),
        )
        add(
            Scenario(
                "SPEAKING/REPEAT: second detection at exact window boundary passes",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0), wwAt(1_200)),
                expected = listOf(ww),
            ),
        )
        add(
            Scenario(
                "SPEAKING/REPEAT: second detection outside window does not pass (window restarts)",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0), wwAt(1_201)),
                expected = emptyList(),
            ),
        )
        add(
            Scenario(
                "SPEAKING/REPEAT: stale window restarts and third detection accepts",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0), wwAt(1_300), wwAt(700)), // 700 after restart candidate
                expected = listOf(ww),
            ),
        )

        // --- Post-accept suppression (replaces old trailing-audio cooldown) ---
        add(
            Scenario(
                "IDLE/REPEAT: detections within postAcceptCooldownMs are suppressed",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.IDLE,
                steps = listOf(wwAt(0), wwAt(300), wwAt(400)), // accept @0, suppressed @300, passes @700
                expected = listOf(ww, ww),
            ),
        )
        add(
            Scenario(
                "SPEAKING/REPEAT: suppression wins over opening a new candidate window",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0), wwAt(100), wwAt(300), wwAt(400)),
                // accept @100; @400 suppressed by cooldown; @800 opens a fresh
                // candidate only -> nothing more passes.
                expected = listOf(ww),
            ),
        )

        // --- DetectorError always passes ungated ---
        add(
            Scenario(
                "SPEAKING/REPEAT: DetectorError passes mid-window and does not disturb candidates",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                steps = listOf(wwAt(0), errAt(10), wwAt(40)),
                expected = listOf(err, ww),
            ),
        )
        add(
            Scenario(
                "IDLE/SINGLE: DetectorError passes even inside post-accept suppression",
                mode = BargeInPolicy.Mode.SINGLE, state = AssistantState.IDLE,
                steps = listOf(wwAt(0), errAt(100), wwAt(200)),
                // WW@200 lands inside the 600 ms suppression window -> dropped.
                expected = listOf(ww, err),
            ),
        )

        // --- Custom policy values are honored ---
        add(
            Scenario(
                "custom repeatWindowMs=100: second detection at 150ms misses the window",
                mode = BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, state = AssistantState.SPEAKING,
                repeatWindowMs = 100,
                steps = listOf(wwAt(0), wwAt(150)),
                expected = emptyList(),
            ),
        )
    }

    private suspend fun run(scenario: Scenario): List<Detection> {
        var now = 0L
        val clock = { now }
        val stateFlow = MutableStateFlow(scenario.state)
        val source = flow {
            for (step in scenario.steps) {
                if (step.advanceMs > 0) {
                    now += step.advanceMs
                    delay(step.advanceMs) // virtual time under runTest
                }
                emit(step.detection)
            }
        }
        val policy = BargeInPolicy(
            mode = scenario.mode,
            repeatWindowMs = scenario.repeatWindowMs,
        )
        return source.gatedBy(policy, stateFlow, clock).toList()
    }

    @Test
    fun `gatedBy decision matrix over states modes and timings`() = runTest {
        val failures = scenarios().mapNotNull { scenario ->
            val actual = run(scenario)
            if (actual == scenario.expected) null
            else "${scenario.name}: expected=${scenario.expected} actual=$actual"
        }
        assertTrue(
            "matrix failures (${failures.size}):\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `gatedBy reacts to live assistant state transitions`() = runTest {
        var now = 0L
        val state = MutableStateFlow(AssistantState.SPEAKING)
        val source = flow {
            emit(ww)                                // candidate opened while speaking
            state.value = AssistantState.LISTENING
            now += 100
            delay(100)
            emit(ww)                                // passes immediately outside SPEAKING
        }
        val passed = source
            .gatedBy(BargeInPolicy(BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK), state) { now }
            .toList()
        assertEquals(listOf(ww), passed)
    }

    @Test
    fun `factory maps config defaults to repeat-during-playback`() {
        val p = BargeInPolicy.from(JarvisConfig())
        assertEquals(BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, p.mode)
        assertEquals(1_200L, p.repeatWindowMs)
        assertEquals(600L, p.postAcceptCooldownMs)
    }

    @Test
    fun `factory honors single-shot flag and custom window`() {
        val single = BargeInPolicy.from(JarvisConfig(bargeInSingleShot = true))
        assertEquals(BargeInPolicy.Mode.SINGLE, single.mode)

        val wide = BargeInPolicy.from(JarvisConfig(bargeInRepeatWindowMs = 999))
        assertEquals(BargeInPolicy.Mode.REPEAT_DURING_PLAYBACK, wide.mode)
        assertEquals(999L, wide.repeatWindowMs)
    }
}
