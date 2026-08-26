package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioRecordSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * m5: the framework's getMinBufferSize result is validated before the mic
 * source is accepted. The decision itself is a pure function so it is fully
 * JVM-testable (the framework call happens only at AudioRecordSource
 * construction on-device).
 */
class AudioRecordSourceTest {

    @Test
    fun `validatedBufferSize accepts positive framework results`() {
        assertEquals(1_280, AudioRecordSource.validatedBufferSize(1_280, 16_000))
        assertEquals(1, AudioRecordSource.validatedBufferSize(1, 16_000))
    }

    @Test
    fun `validatedBufferSize rejects zero with a clear error`() {
        val e = runCatching {
            AudioRecordSource.validatedBufferSize(0, 16_000)
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
        assertTrue(
            "message should name the framework call: ${e?.message}",
            e?.message?.contains("getMinBufferSize") == true,
        )
        assertTrue(
            "message should carry the sample rate: ${e?.message}",
            e?.message?.contains("16000") == true,
        )
    }

    @Test
    fun `validatedBufferSize rejects negative framework results`() {
        for (raw in listOf(-1, Int.MIN_VALUE)) {
            val e = runCatching {
                AudioRecordSource.validatedBufferSize(raw, 24_000)
            }.exceptionOrNull()
            assertTrue("raw=$raw should be rejected", e is IllegalStateException)
        }
    }
}
