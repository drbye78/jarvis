package com.jarvis.assistant

import com.jarvis.assistant.model.AssistantState
import com.jarvis.assistant.ui.OrbShape
import com.jarvis.assistant.ui.OrbState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * State -> shape mapping (U12). The orb's shape channel is pure logic; this
 * pins it so the "deaf and muted must not rely on colour" contract cannot
 * silently regress.
 */
class OrbShapeTest {

    @Test
    fun `deaf wins over muted and over any session state`() {
        assertEquals(
            OrbState.DEAF,
            OrbShape.of(AssistantState.LISTENING, muted = true, deaf = true),
        )
    }

    @Test
    fun `muted wins over any session state`() {
        assertEquals(
            OrbState.MUTED,
            OrbShape.of(AssistantState.SPEAKING, muted = true, deaf = false),
        )
    }

    @Test
    fun `no bound state renders idle`() {
        assertEquals(OrbState.IDLE, OrbShape.of(null, muted = false, deaf = false))
    }

    @Test
    fun `every session state maps to its own shape`() {
        assertEquals(OrbState.IDLE, OrbShape.of(AssistantState.IDLE, false, false))
        assertEquals(OrbState.LISTENING, OrbShape.of(AssistantState.LISTENING, false, false))
        assertEquals(OrbState.THINKING, OrbShape.of(AssistantState.THINKING, false, false))
        assertEquals(OrbState.SPEAKING, OrbShape.of(AssistantState.SPEAKING, false, false))
        assertEquals(
            OrbState.FOLLOW_UP,
            OrbShape.of(AssistantState.FOLLOW_UP_WINDOW, false, false),
        )
    }

    @Test
    fun `every shape is reachable and distinct`() {
        val reachable = setOf(
            OrbShape.of(AssistantState.IDLE, false, false),
            OrbShape.of(AssistantState.LISTENING, false, false),
            OrbShape.of(AssistantState.THINKING, false, false),
            OrbShape.of(AssistantState.SPEAKING, false, false),
            OrbShape.of(AssistantState.FOLLOW_UP_WINDOW, false, false),
            OrbShape.of(AssistantState.IDLE, muted = true, deaf = false),
            OrbShape.of(AssistantState.IDLE, muted = false, deaf = true),
        )
        assertEquals(OrbState.entries.toSet(), reachable)
    }

    @Test
    fun `follow-up arc floor keeps the shape channel alive`() {
        assertTrue(OrbShape.MIN_FOLLOW_UP_ARC_DEGREES > 0f)
        assertTrue(OrbShape.MIN_FOLLOW_UP_ARC_DEGREES < 360f)
    }
}
