package com.jarvis.assistant

import com.jarvis.assistant.tools.WriteConfirmation
import com.jarvis.assistant.tools.WriteGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The single-use write-confirmation state machine, pinned in the exact order a
 * session drives it: arm on the requested turn, then require an ASR
 * affirmative on the IMMEDIATELY-NEXT turn. A fixed/mutable clock keeps TTL
 * deterministic.
 */
class WriteConfirmationTest {

    private var now: Long = 1_000L
    private fun store() = WriteConfirmation(clock = { now })

    /** Arm `key` on turn 1 via one non-affirmative-but-valid utterance. */
    private fun WriteConfirmation.arm(key: String, utterance: String = "удали") {
        noteTurnStart(1)
        noteUserUtterance(1, utterance)
        assertEquals(WriteGate.NeedsConfirmation, gate(key, 1))
    }

    @Test
    fun `a same-turn gate arms but never confirms`() {
        val wc = store()
        wc.noteTurnStart(1)
        wc.noteUserUtterance(1, "удали")
        assertEquals(WriteGate.NeedsConfirmation, wc.gate("keyA", 1))
        assertEquals("a second same-turn gate must still not confirm", WriteGate.NeedsConfirmation, wc.gate("keyA", 1))
    }

    @Test
    fun `an affirmative on the immediately-next turn confirms`() {
        val wc = store()
        wc.arm("keyA")

        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "да")
        assertEquals(WriteGate.Confirmed, wc.gate("keyA", 2))
    }

    @Test
    fun `a confirmed challenge is single-use`() {
        val wc = store()
        wc.arm("keyA")
        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "да")
        assertEquals(WriteGate.Confirmed, wc.gate("keyA", 2))
        assertEquals("there is no confirmation for a second call", WriteGate.NeedsConfirmation, wc.gate("keyA", 2))
    }

    @Test
    fun `a non-affirmative next turn never confirms`() {
        listOf("нет", "а что это?", "расскажи о погоде", "не надо").forEach { utterance ->
            val wc = store()
            wc.arm("keyA")
            wc.noteTurnStart(2)
            wc.noteUserUtterance(2, utterance)
            assertNotEquals(
                "«$utterance» must not confirm",
                WriteGate.Confirmed,
                wc.gate("keyA", 2),
            )
        }
    }

    @Test
    fun `an intervening utterance creates an ordinal gap and cannot confirm`() {
        val wc = store()
        wc.arm("keyA")

        // Turn 2 passes without a confirmation (a different utterance).
        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "какая погода")

        // Turn 3 says yes — but the challenge must be confirmed on turn 2 only.
        wc.noteTurnStart(3)
        wc.noteUserUtterance(3, "да")
        assertNotEquals(WriteGate.Confirmed, wc.gate("keyA", 3))
    }

    @Test
    fun `a turn skipped without any utterance also cannot confirm`() {
        val wc = store()
        wc.arm("keyA")

        // Turn 2 begins but produces no ASR final at all.
        wc.noteTurnStart(2)

        wc.noteTurnStart(3)
        wc.noteUserUtterance(3, "да")
        assertNotEquals(WriteGate.Confirmed, wc.gate("keyA", 3))
    }

    @Test
    fun `a challenge older than the TTL cannot confirm`() {
        val wc = store()
        wc.arm("keyA")

        now += WriteConfirmation.DEFAULT_TTL_MS + 1
        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "да")
        assertNotEquals(WriteGate.Confirmed, wc.gate("keyA", 2))
    }

    @Test
    fun `the armed key cannot be swapped within the arming turn`() {
        val wc = store()
        wc.arm("keyA")

        // A different key on the SAME turn must not overwrite the armed one.
        assertEquals(WriteGate.NeedsConfirmation, wc.gate("keyB", 1))

        // Turn 2's yes confirms keyA, not keyB.
        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "да")
        assertNotEquals("keyB can never be confirmed", WriteGate.Confirmed, wc.gate("keyB", 2))
    }

    @Test
    fun `only the originally armed key is confirmable`() {
        val wc = store()
        wc.arm("keyA")
        assertEquals(WriteGate.NeedsConfirmation, wc.gate("keyB", 1))

        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "да")
        assertEquals("keyA is the armed key", WriteGate.Confirmed, wc.gate("keyA", 2))
    }

    @Test
    fun `a null turn is denied`() {
        val wc = store()
        assertEquals(WriteGate.Denied, wc.gate("keyA", null))
        assertEquals(WriteGate.Denied, wc.gate(null, null))
    }

    @Test
    fun `a null key never arms and defers to a real key`() {
        val wc = store()
        wc.noteTurnStart(1)
        wc.noteUserUtterance(1, "удали")
        assertEquals(WriteGate.NeedsConfirmation, wc.gate(null, 1))

        // Nothing was armed by the null key: a real key arms cleanly.
        assertEquals(WriteGate.NeedsConfirmation, wc.gate("keyA", 1))
        wc.noteTurnStart(2)
        wc.noteUserUtterance(2, "да")
        assertEquals(WriteGate.Confirmed, wc.gate("keyA", 2))
    }
}
