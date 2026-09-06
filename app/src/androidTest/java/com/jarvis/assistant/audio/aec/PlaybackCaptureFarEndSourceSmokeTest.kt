package com.jarvis.assistant.audio.aec

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REMEDIATION_PLAN P1.7: DEVICE-ONLY smoke for the API-29 playback-capture
 * far-end lane.
 *
 * REQUIRES A DEVICE/EMULATOR — NOT CI-RUNNABLE (CI only compiles this source
 * set via :app:assembleDebugAndroidTest).
 *
 * Scope (honest): construction + cleanup guards only. [PlaybackCaptureFarEndSource
 * .start] needs a CONSENTED MediaProjection result AND the mediaProjection
 * foreground-service type promoted on the service first (see its KDoc) — an
 * instrumentation test cannot grant that consent dialog, so start() is not
 * exercised here. What IS pinned: a never-started lane stops cleanly (twice),
 * stays not-running, fed zero frames, and its diagnostics line renders.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCaptureFarEndSourceSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun construction_isHarmless_andNeverStartedStaysNotRunning() {
        val source = PlaybackCaptureFarEndSource(context, NoopEchoCanceller, kotlinx.coroutines.MainScope())
        assertFalse("a constructed lane must not be running", source.running)
        assertEquals(0L, source.framesFed)
    }

    @Test
    fun stop_isIdempotent_andSafeWithoutStart() {
        val source = PlaybackCaptureFarEndSource(context, NoopEchoCanceller, kotlinx.coroutines.MainScope())
        source.stop() // never started — must be a no-op, not a crash
        source.stop()
        assertFalse(source.running)
        assertEquals(0L, source.framesFed)
    }

    @Test
    fun diagLine_rendersTheLaneState() {
        val source = PlaybackCaptureFarEndSource(context, NoopEchoCanceller, kotlinx.coroutines.MainScope())
        val line = source.diagLine()
        assertNotNull(line)
        assertTrue("diag line must mention the running flag", line.contains("running=false"))
    }

    @Test
    fun consentIntent_isAvailableOnApi29Plus() {
        val source = PlaybackCaptureFarEndSource(context, NoopEchoCanceller, kotlinx.coroutines.MainScope())
        // minSdk 29: the API is always supported on the supported floor.
        assertTrue(PlaybackCaptureFarEndSource.apiSupported())
        val intent = source.createConsentIntent()
        assertNotNull("consent intent must be resolvable on API 29+", intent)
    }
}
