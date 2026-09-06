package com.jarvis.assistant.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * REMEDIATION_PLAN P1.7: DEVICE-ONLY smoke coverage for the Sherpa-ONNX wake
 * word lane.
 *
 * REQUIRES A DEVICE/EMULATOR — NOT CI-RUNNABLE (the CI gate runs only
 * :app:assembleDebugAndroidTest for this source set; the JVM suite never
 * loads the sherpa-onnx native library or the LFS-tracked model assets).
 * AGENTS.md: `git lfs pull` after clone — these tests read the bundled
 * model assets under `assets/sherpa_kws/`.
 *
 * Scope (honest): no-crash construction + expected success for
 * [SherpaModelStore] extraction and [SherpaKwsEngine] build — BOTH loading
 * modes (asset `newFromAsset` Mode A, filesystem `newFromFile` Mode B), and
 * one silence frame through the engine (no keyword → -1, not a
 * self-trigger). Keyword DETECTION accuracy (audio in → matched id) is not
 * asserted here — it needs a real microphone and lives in the detector
 * integration story.
 *
 * modelType is intentionally NOT forced (the bundled models are zipformer v1;
 * auto-detect from ONNX metadata — commit 8b35cb7). Never reintroduce it.
 */
@RunWith(AndroidJUnit4::class)
class SherpaSmokeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val entries = listOf(SherpaKeywords.wake(), SherpaKeywords.stop())

    @Test
    fun modelStore_extractsBundledAssets_toACompleteDirectory() {
        val store = SherpaModelStore(context)
        val dir: File = store.ensureExtracted()

        assertTrue("extraction dir must exist: $dir", dir.isDirectory)
        for (name in SherpaModelStore.ASSET_FILES) {
            val f = File(dir, name)
            assertTrue("missing extracted model file: $name", f.isFile)
            assertTrue("extracted asset '$name' is empty", f.length() > 0)
        }

        // Idempotency: a second call must succeed without re-crashing (the
        // size/version short-circuit path).
        val again: File = SherpaModelStore(context).ensureExtracted()
        assertTrue(again.isDirectory)
    }

    @Test
    fun bundledEngine_buildsFromAssets_spotterSilentOnZeros_releasesCleanly() {
        val engine = SherpaKwsEngine(
            context = context,
            sensitivity = 0.5f,
            entries = entries,
        )
        // FIXPLAN B: the engine exposes its phrases in order.
        assertEquals(listOf("jarvis", "stop"), engine.phrases.map { it.id })

        val silence = ShortArray(16_000) // 1 s of zeros
        assertEquals(-1, engine.process(silence))
        engine.release()
    }

    @Test
    fun directoryEngine_buildsViaNewFromFile_afterExtraction_releasesCleanly() {
        // The Mode B path behind custom wake words: extracted model dir +
        // GENERATED keywords content, written into a work dir.
        val dir = SherpaModelStore(context).ensureExtracted()
        val workDir = File(context.cacheDir, "sherpa_smoke_work").apply { mkdirs() }
        val keywords = SherpaKeywords.toKeywordsFileContent(entries)

        val engine = SherpaKwsEngine(
            context = context,
            sensitivity = 0.5f,
            entries = entries,
            modelSource = SherpaModelSource.Directory(dir.absolutePath, provider = "xnnpack"),
            generatedKeywordsContent = keywords,
            workDir = workDir,
        )
        assertEquals(listOf("jarvis", "stop"), engine.phrases.map { it.id })
        assertEquals(-1, engine.process(ShortArray(16_000)))
        engine.release()

        // The generated keywords file is the file constructor's input.
        val generated = File(workDir, SherpaKwsEngine.GENERATED_KEYWORDS_FILE_NAME)
        assertTrue(generated.isFile)
        assertTrue(generated.length() > 0)
    }

    @Test
    fun engineRefusesToBuild_whenBundledModeHasNoContext() {
        // Bundled mode REQUIRES a context (AssetManager) — a null context is
        // a failed build surfaced as an exception, never a silent dead engine.
        try {
            SherpaKwsEngine(context = null, sensitivity = 0.5f, entries = entries)
            throw AssertionError("null-context bundled build must fail")
        } catch (expected: IllegalStateException) {
            assertNotNull(expected.message)
        }
    }
}
