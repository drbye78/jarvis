package com.jarvis.assistant.audio

import android.content.Context
import timber.log.Timber
import java.io.File

/**
 * One-time extraction of the bundled Sherpa-ONNX KWS model from APK assets
 * into `filesDir`, so [SherpaKwsEngine] can build via the AAR's `newFromFile`
 * constructor with a GENERATED keywords file (custom wake words, FIXPLAN C).
 *
 * The asset loading path (`newFromAsset`) stays the zero-config default —
 * extraction happens only when a custom keyword or a generated keyword set
 * is actually requested. Files are copied when missing or size-changed, so
 * an interrupted extraction heals on the next attempt, and [EXTRACT_VERSION]
 * forces a fresh copy when the shipped model set ever changes.
 */
class SherpaModelStore(private val context: Context) {

    /** Extraction target (inside our own filesDir — always writable). */
    fun targetDir(): File = File(context.filesDir, "sherpa_kws_model")

    /**
     * Ensure every model file is present on the filesystem; returns the
     * directory. Throws [IllegalStateException] when a required file cannot
     * be materialized — the wake-word build surfaces that as
     * [com.jarvis.assistant.contracts.DetectorState.Failed] with the reason,
     * never as a silent dead wake word.
     */
    fun ensureExtracted(): File {
        val dir = targetDir()
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("Cannot create Sherpa model dir: $dir")
        }
        val versionMarker = File(dir, "extract_version.txt")
        val needsVersionRefresh =
            !versionMarker.isFile || versionMarker.readText().trim() != EXTRACT_VERSION.toString()

        if (needsVersionRefresh) {
            // Version bump: wipe and re-extract everything (a model file may
            // have changed content without changing size).
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }

        for (name in ASSET_FILES) {
            val target = File(dir, name)
            val assetSize = assetSize(name)
            if (target.isFile && !needsVersionRefresh &&
                assetSize in 0..Long.MAX_VALUE && target.length() == assetSize
            ) {
                continue
            }
            copyAsset(name, target)
        }
        versionMarker.writeText(EXTRACT_VERSION.toString())
        return dir
    }

    private fun copyAsset(assetName: String, target: File) {
        try {
            context.assets.open("$ASSET_DIR/$assetName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to extract Sherpa asset '$assetName': ${e.message}", e)
        }
        if (target.length() <= 0) {
            throw IllegalStateException("Extracted Sherpa asset '$assetName' is empty")
        }
        Timber.d("SherpaModelStore: extracted %s (%d bytes)", assetName, target.length())
    }

    /**
     * Uncompressed size of an asset when available (-1 when the APK stores
     * it compressed). A -1 simply forces the copy path — correctness first.
     */
    private fun assetSize(assetName: String): Long = try {
        // AssetFileDescriptor exposes the declared length; compressed assets
        // report UNKNOWN_LENGTH, which the caller treats as "always copy".
        context.assets.openFd("$ASSET_DIR/$assetName").use { it.length }
    } catch (e: Exception) {
        -1L
    }

    companion object {
        private const val ASSET_DIR = SherpaKeywords.ASSET_DIR

        /**
         * Bump when the shipped model files change, so stale extractions
         * re-copy instead of silently mixing old and new model files.
         */
        private const val EXTRACT_VERSION = 1

        /** The minimal file set the transducer KWS needs (int8 runtime set). */
        val ASSET_FILES = listOf(
            "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
            "tokens.txt",
            "bpe.model",
        )
    }
}
