package com.jarvis.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * The wake-word `.ppn` import contract, with the failure paths that the old
 * inline copy in `SettingsActivity.onActivityResult` got wrong: a null stream
 * skipped the copy while the caller still persisted the model path, and an
 * `IOException` escaped uncaught. Both are asserted here to install NOTHING and
 * to leave a previously working model untouched.
 */
class WakeWordImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun destination(): File = File(temp.root, WakeWordImport.PPN_FILE_NAME)

    private fun writer(): (File) -> OutputStream = { file -> file.outputStream() }

    /** Everything in the import directory that is not the installed model. */
    private fun strays(): List<String> =
        temp.root.listFiles()
            ?.map { it.name }
            ?.filter { it != WakeWordImport.PPN_FILE_NAME }
            .orEmpty()

    private fun giveExistingModel(): Unit = destination().writeBytes("OLD MODEL".toByteArray())

    @Test
    fun `ppn file names are recognised case-insensitively`() {
        assertTrue(WakeWordImport.isPpnFileName("jarvis.ppn"))
        assertTrue(WakeWordImport.isPpnFileName("JARVIS.PPN"))
        assertFalse(WakeWordImport.isPpnFileName("jarvis.ogg"))
        assertFalse(WakeWordImport.isPpnFileName("ppn"))
        assertFalse(WakeWordImport.isPpnFileName(null))
    }

    @Test
    fun `a valid source is installed and reported with its size`() {
        val bytes = ByteArray(64) { it.toByte() }

        val outcome = WakeWordImport.install(bytes.inputStream(), destination(), writer())

        assertEquals(WakeWordImport.Outcome.Copied(64), outcome)
        assertTrue(destination().readBytes().contentEquals(bytes))
    }

    @Test
    fun `an existing model is replaced on a successful import`() {
        giveExistingModel()

        val outcome = WakeWordImport.install("NEW".byteInputStream(), destination(), writer())

        assertEquals(WakeWordImport.Outcome.Copied(3), outcome)
        assertEquals("NEW", destination().readText())
    }

    @Test
    fun `a null source reports no-source and leaves the existing model alone`() {
        giveExistingModel()

        val outcome = WakeWordImport.install(null, destination(), writer())

        assertEquals(WakeWordImport.Outcome.NoSource, outcome)
        assertEquals("OLD MODEL", destination().readText())
    }

    @Test
    fun `an empty source is rejected rather than installed`() {
        giveExistingModel()

        val outcome = WakeWordImport.install(ByteArray(0).inputStream(), destination(), writer())

        // The old code's silent skip produced exactly this shape of failure:
        // a zero-byte file the detector would then try to load.
        assertEquals(WakeWordImport.Outcome.Failed("empty"), outcome)
        assertEquals("OLD MODEL", destination().readText())
    }

    @Test
    fun `a failure to open the output is reported and does not clobber the existing model`() {
        giveExistingModel()

        val outcome = WakeWordImport.install(
            "NEW".byteInputStream(),
            destination(),
            { throw IOException("disk full") },
        )

        assertEquals(WakeWordImport.Outcome.Failed("IOException"), outcome)
        assertEquals("OLD MODEL", destination().readText())
    }

    @Test
    fun `a mid-copy failure is reported and does not clobber the existing model`() {
        giveExistingModel()

        val outcome = WakeWordImport.install(
            TruncatedInputStream(failAfterBytes = 4),
            destination(),
            writer(),
        )

        assertEquals(WakeWordImport.Outcome.Failed("IOException"), outcome)
        assertEquals("OLD MODEL", destination().readText())
    }

    @Test
    fun `no partial file survives a success or any failure`() {
        WakeWordImport.install("NEW".byteInputStream(), destination(), writer())
        assertTrue("success left litter: ${strays()}", strays().isEmpty())

        WakeWordImport.install(ByteArray(0).inputStream(), destination(), writer())
        assertTrue("empty-source failure left litter: ${strays()}", strays().isEmpty())

        WakeWordImport.install(TruncatedInputStream(failAfterBytes = 2), destination(), writer())
        assertTrue("mid-copy failure left litter: ${strays()}", strays().isEmpty())

        WakeWordImport.install("NEW".byteInputStream(), destination(), { throw IOException("boom") })
        assertTrue("open failure left litter: ${strays()}", strays().isEmpty())
    }

    @Test
    fun `outcome labels are content-free`() {
        assertEquals("copied", WakeWordImport.Outcome.Copied(1).describe())
        assertEquals("no-source", WakeWordImport.Outcome.NoSource.describe())
        assertEquals("failed:empty", WakeWordImport.Outcome.Failed("empty").describe())
    }

    /** Emits [failAfterBytes] bytes, then fails — a truncated provider read. */
    private class TruncatedInputStream(private val failAfterBytes: Int) : InputStream() {
        private var emitted = 0

        override fun read(): Int {
            if (emitted >= failAfterBytes) throw IOException("truncated")
            emitted++
            return 'x'.code
        }
    }
}
