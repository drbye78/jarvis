package com.jarvis.assistant

import com.jarvis.assistant.audio.AudioRecordSource
import com.jarvis.assistant.audio.CaptureHandle
import com.jarvis.assistant.contracts.AudioSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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

/**
 * A fake capture session whose [read] parks on a gate exactly like a blocking
 * native `AudioRecord.read`, and which RECORDS every [shutdown] — flagging
 * the cardinal sin (releasing the record while a read is inside it).
 */
private class FakeCaptureHandle(
    private val frameLen: Int = 320,
) : CaptureHandle {
    /** Negative return = HAL error, if the test arms it. */
    @Volatile var errorCode: Int = 0

    /** Counted down once [read] has entered its blocking window. */
    val readEntered = CountDownLatch(1)

    /** [read] parks here until the test releases it. */
    val readGate = CountDownLatch(1)

    val shutdowns = AtomicInteger()

    @Volatile
    var readInFlight = false
        private set

    @Volatile
    var shutdownDuringRead = false
        private set

    val reads = AtomicInteger()

    override fun read(dest: ShortArray, offset: Int, length: Int): Int {
        reads.incrementAndGet()
        readInFlight = true
        readEntered.countDown()
        readGate.await(5, TimeUnit.SECONDS)
        readInFlight = false
        if (errorCode < 0) return errorCode
        for (i in 0 until minOf(frameLen, dest.size - offset)) {
            dest[offset + i] = (i + 1).toShort()
        }
        return frameLen
    }

    override fun shutdown() {
        shutdowns.incrementAndGet()
        if (readInFlight) shutdownDuringRead = true
    }
}

/**
 * P1-S #4(d) (audit 2026-09-16): AudioRecord teardown must never race a read
 * inside native code (OEM-dependent SIGSEGV), must never park stop() on that
 * read either (binder/main-thread callers), must release the EXACT handle a
 * reader was in (a re-start in between publishes a different one), and must
 * leak nothing. Driven through the [CaptureHandle] seam.
 */
class AudioRecordSourceTeardownTest {

    private fun joinReader(reader: Thread, timeoutMs: Long = 5_000) {
        reader.join(timeoutMs)
        assertFalse("reader thread must finish within ${timeoutMs}ms", reader.isAlive)
    }

    @Test
    fun `stop while a read is in flight defers release to the last reader`() {
        val handle = FakeCaptureHandle()
        val source = AudioRecordSource(AudioSpec.MIC) { handle }
        source.start()

        val readerError = AtomicReference<Throwable?>()
        val reader = Thread {
            try {
                val frame = source.read()
                assertEquals("reader must still get its full frame", 320, frame.size)
            } catch (t: Throwable) {
                readerError.set(t)
            }
        }
        reader.start()
        assertTrue("read must enter the fake native window", handle.readEntered.await(5, TimeUnit.SECONDS))

        // (a) stop() must NOT release underneath the in-flight read…
        val stopStart = System.currentTimeMillis()
        source.stop()
        val stopElapsed = System.currentTimeMillis() - stopStart
        assertEquals(
            "record released while a read was inside it (use-after-free on OEM ROMs)",
            0,
            handle.shutdowns.get(),
        )
        // (b) …and must NOT wait for the read either — stop returns promptly.
        assertTrue("stop() blocked $stopElapsed ms on the in-flight read", stopElapsed < 1_000)

        // (c) The last exiting reader performs the release, exactly once.
        handle.readGate.countDown()
        joinReader(reader)
        assertEquals("parked handle must be released by the last reader", 1, handle.shutdowns.get())
        assertFalse(handle.shutdownDuringRead)
        readerError.let { assertTrue("reader failed: ${it.get()}", it.get() == null) }

        // (d) Closed source → the contract's IllegalStateException (the
        // producer's clean-exit signal in AudioPipeline), never a silent spin.
        val e = runCatching { source.read() }.exceptionOrNull()
        assertTrue("expected IllegalStateException, got $e", e is IllegalStateException)
    }

    @Test
    fun `restart while the old read is parked releases exactly the old handle`() {
        val h1 = FakeCaptureHandle()
        val h2 = FakeCaptureHandle()
        val handles = listOf(h1, h2)
        val next = AtomicInteger()
        val source = AudioRecordSource(AudioSpec.MIC) { handles[next.getAndIncrement()] }
        source.start() // publishes h1

        val oldReader = Thread { runCatching { source.read() } }
        oldReader.start()
        assertTrue(h1.readEntered.await(5, TimeUnit.SECONDS))

        source.stop() // parks h1 (reader in flight)
        source.start() // publishes h2 while h1 is still parked
        assertEquals(0, h1.shutdowns.get())
        assertEquals(0, h2.shutdowns.get())

        // A fresh read uses the NEW handle, not the parked one.
        val newReader = Thread { runCatching { source.read() } }
        newReader.start()
        assertTrue("new reader must run on the freshly published handle", h2.readEntered.await(5, TimeUnit.SECONDS))
        assertEquals(1, h2.reads.get())

        // Old reader exits first: one reader (h2) is still in flight, so the
        // parked h1 must keep waiting…
        h1.readGate.countDown()
        joinReader(oldReader)
        assertEquals("h1 teardown must wait for the LAST reader", 0, h1.shutdowns.get())

        // …and lands the instant it is the last one.
        h2.readGate.countDown()
        joinReader(newReader)
        assertEquals(1, h1.shutdowns.get())
        assertFalse("wrong handle released", h1.shutdownDuringRead)
        assertEquals("still-published handle must NOT be released", 0, h2.shutdowns.get())

        // Final stop with no readers: inline teardown, no leak.
        source.stop()
        assertEquals(1, h2.shutdowns.get())
    }

    @Test
    fun `every opened handle is released exactly once across stop cycles`() {
        val opened = Collections.synchronizedList(mutableListOf<FakeCaptureHandle>())
        val source = AudioRecordSource(AudioSpec.MIC) {
            FakeCaptureHandle().also { opened.add(it) }
        }
        repeat(20) {
            source.start()
            val reader = Thread { runCatching { source.read() } }
            reader.start()
            opened.last().readEntered.await(5, TimeUnit.SECONDS)
            source.stop()
            opened.last().readGate.countDown()
            joinReader(reader)
        }
        source.stop()
        for ((i, h) in opened.withIndex()) {
            assertEquals("handle #$i leaked or double-released", 1, h.shutdowns.get())
            assertFalse("handle #$i released mid-read", h.shutdownDuringRead)
        }
    }

    @Test
    fun `negative read surfaces as IOException and forces a fresh handle on restart`() {
        val h1 = FakeCaptureHandle().apply { errorCode = -3 } // ERROR_INVALID_OPERATION-ish
        val h2 = FakeCaptureHandle()
        val handles = listOf(h1, h2)
        val next = AtomicInteger()
        val source = AudioRecordSource(AudioSpec.MIC) { handles[next.getAndIncrement()] }
        source.start()
        h1.readGate.countDown()
        val e = runCatching { source.read() }.exceptionOrNull()
        assertTrue("HAL error must surface as IOException, got $e", e is java.io.IOException)

        // The watchdog-revive path: start() after a read error must NOT reuse
        // the broken record — it opens a fresh session.
        source.stop()
        assertEquals(1, h1.shutdowns.get())
        source.start()
        h2.readGate.countDown()
        val frame = source.read()
        assertEquals("fresh handle must deliver audio", 320, frame.size)
        assertEquals(1.toShort(), frame[0])
    }
}
