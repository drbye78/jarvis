package com.jarvis.assistant

import com.jarvis.assistant.ui.PrimaryControl
import com.jarvis.assistant.ui.PrimaryControl.State
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The primary (start/stop/resume) control's precedence, pure JVM.
 *
 * The regression this pins: an explicit stop calls
 * `JarvisForegroundService.explicitStop()`, setting `userStopped=true`, which
 * suppresses the watchdog revive. If the toggle resolved `running` first, a
 * stopped-but-still-bound (or running-but-deaf) assistant would read
 * «Остановить» forever — with no way back to listening, because only an
 * explicit start clears the flag.
 */
class PrimaryControlTest {

    @Test
    fun `userStopped outranks a still-bound running graph`() {
        assertEquals(
            State.RESUMED,
            PrimaryControl.state(userStopped = true, running = true, serviceAttached = true),
        )
    }

    @Test
    fun `userStopped outranks every other combination`() {
        for (running in listOf(true, false)) {
            for (attached in listOf(true, false)) {
                assertEquals(
                    "userStopped must win for running=$running attached=$attached",
                    State.RESUMED,
                    PrimaryControl.state(userStopped = true, running = running, serviceAttached = attached),
                )
            }
        }
    }

    @Test
    fun `running without a stop flag offers stop`() {
        assertEquals(
            State.STOPPABLE,
            PrimaryControl.state(userStopped = false, running = true, serviceAttached = true),
        )
    }

    @Test
    fun `attached without a graph is bootstrapping`() {
        assertEquals(
            State.BOOTSTRAPPING,
            PrimaryControl.state(userStopped = false, running = false, serviceAttached = true),
        )
    }

    @Test
    fun `nothing attached is startable`() {
        assertEquals(
            State.STARTABLE,
            PrimaryControl.state(userStopped = false, running = false, serviceAttached = false),
        )
    }
}
