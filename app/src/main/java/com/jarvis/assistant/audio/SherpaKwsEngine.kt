package com.jarvis.assistant.audio

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import timber.log.Timber
import java.io.File

/**
 * Where the transducer model (and its tokens/bpe vocab) come from.
 *
 * FIXPLAN C: the bundled AAR exports BOTH native constructors —
 * `newFromAsset` (verified in libsherpa-onnx-jni.so) AND `newFromFile` — so
 * filesystem models work. The asset path stays the zero-config default;
 * the file path enables generated keyword files (custom wake words) and
 * user-supplied model directories.
 */
sealed interface SherpaModelSource {
    /** Bundled APK assets; keyword files must also be asset-relative. */
    data object Bundled : SherpaModelSource

    /**
     * A filesystem model directory (the [SherpaModelStore] extraction of the
     * bundled model, or a user-supplied model). [provider] selects the ONNX
     * execution provider: "xnnpack" for the known bundled model, "" (system
     * default CPU) for arbitrary user models.
     */
    data class Directory(val dir: String, val provider: String = "") : SherpaModelSource
}

/**
 * Fully on-device wake-word engine backed by Sherpa-ONNX Keyword Spotting
 * (zipformer2 transducer, xnnpack CPU backend). No account, no network.
 *
 * Keyword identity (FIXPLAN B): the engine is built from ordered
 * [SherpaKeywords.Entry] items; the native `KeywordSpotterResult.keyword`
 * (the matched token line) is mapped back through whitespace-normalized
 * (and space-stripped) comparison, so a result that arrives space-joined or
 * compact still resolves to the right phrase. A non-empty result that
 * matches NOTHING is treated as -1 (never as the wake phrase) — a keywords
 * file whose lines disagree with [entries] degrades to silence, not to a
 * self-triggering assistant.
 *
 * The sensitivity→threshold mapping is unchanged (M5): higher sensitivity →
 * lower `keywordsThreshold` → easier trigger.
 */
class SherpaKwsEngine(
    context: Context?,
    sensitivity: Float,
    /** Ordered keyword entries; exposes them via [WakeWordEngine.phrases]. */
    val entries: List<SherpaKeywords.Entry>,
    private val modelSource: SherpaModelSource = SherpaModelSource.Bundled,
    /** Bundled mode: asset-relative keywords file. */
    private val bundledKeywordsAsset: String = SherpaKeywords.ASSET_KEYWORDS_FILE,
    /**
     * Directory mode: full keywords-file content to write under [workDir].
     * Required when [modelSource] is [SherpaModelSource.Directory] — the
     * file constructor reads keywords from the filesystem, which is exactly
     * what makes custom wake words possible.
     */
    private val generatedKeywordsContent: String? = null,
    /** Directory mode: writable directory for the generated keywords file. */
    private val workDir: File? = null,
) : WakeWordEngine {

    // M5: map the 0.0–1.0 sensitivity to Sherpa's `keywordsThreshold`.
    private val keywordsThreshold = 0.25f + (1f - sensitivity.coerceIn(0f, 1f)) * 0.5f

    override val phrases: List<WakeWordEngine.Phrase> =
        entries.map { WakeWordEngine.Phrase(id = it.id, isStop = it.isStop) }

    private val spotter: KeywordSpotter
    private val stream: OnlineStream

    init {
        when (modelSource) {
            is SherpaModelSource.Bundled -> {
                val assetManager: AssetManager = requireNotNull(context?.assets) {
                    "Context (for AssetManager) required to build the bundled Sherpa-ONNX KeywordSpotter"
                }
                // All paths RELATIVE to the assets root (Mode A). An absolute
                // path here crashes natively (AAssetManager_open) — never
                // pass one into newFromAsset.
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = bundledModelConfig(),
                    keywordsFile = bundledKeywordsAsset,
                    keywordsScore = SherpaKeywords.KEYWORDS_SCORE,
                    keywordsThreshold = keywordsThreshold,
                    numTrailingBlanks = 2,
                )
                spotter = createSpotter(config) { KeywordSpotter(assetManager, config) }
            }

            is SherpaModelSource.Directory -> {
                val dir = File(modelSource.dir)
                val generated = requireNotNull(generatedKeywordsContent) {
                    "Directory-mode Sherpa engines need generated keywords content"
                }
                val work = requireNotNull(workDir) {
                    "Directory-mode Sherpa engines need a writable work directory"
                }
                val keywordsFile = writeKeywordsFile(work, generated)
                val config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
                    modelConfig = fileModelConfig(dir, modelSource.provider),
                    keywordsFile = keywordsFile.absolutePath,
                    keywordsScore = SherpaKeywords.KEYWORDS_SCORE,
                    keywordsThreshold = keywordsThreshold,
                    numTrailingBlanks = 2,
                )
                // Validate BEFORE the native call: a bad path inside
                // newFromFile is a fatal native exit, not an exception.
                config.modelConfig.let { mc ->
                    listOf(mc.transducer.encoder, mc.transducer.decoder, mc.transducer.joiner, mc.tokens)
                        .forEach { path ->
                            require(!path.isNullOrBlank() && File(path).isFile) {
                                "Sherpa model file missing: $path"
                            }
                        }
                }
                // FIXPLAN C: file constructor — the AAR's Kotlin ctor is
                // `KeywordSpotter(assetManager: AssetManager?, config)` with
                // NO default (the old AGENTS.md note was right about that
                // part); passing NULL assetManager routes to native
                // `newFromFile(config)`, which reads absolute paths.
                spotter = createSpotter(config) { KeywordSpotter(null, config) }
            }
        }

        // L1: a failure to create the stream would otherwise leak the native
        // spotter — release it before propagating the error.
        try {
            stream = spotter.createStream()
        } catch (e: Exception) {
            runCatching { spotter.release() }
            Timber.e(e, "Sherpa KWS stream create failed")
            throw IllegalStateException("Sherpa stream failed to create: ${e.message}", e)
        }
    }

    private fun bundledModelConfig(): OnlineModelConfig = OnlineModelConfig(
        transducer = OnlineTransducerModelConfig(
            encoder = ASSET_ENCODER_INT8,
            decoder = "sherpa_kws/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
            joiner = "sherpa_kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        ),
        tokens = "sherpa_kws/tokens.txt",
        bpeVocab = "sherpa_kws/bpe.model",
        modelingUnit = "bpe",
        numThreads = 4,
        provider = "xnnpack",
        modelType = "zipformer2",
    )

    private fun fileModelConfig(dir: File, provider: String): OnlineModelConfig {
        // Prefer the int8 encoder/joiner when the model dir ships both
        // variants (the bundled extraction does); else the plain ones —
        // user-supplied models may ship either.
        fun pick(vararg names: String): String =
            names.map { File(dir, it) }.firstOrNull { it.isFile }?.absolutePath
                ?: File(dir, names.first()).absolutePath // surfaces a clear "missing" in the validator

        return OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = pick(
                    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    "encoder.onnx",
                ),
                decoder = pick(
                    "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    "decoder.onnx",
                ),
                joiner = pick(
                    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    "joiner.onnx",
                ),
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            bpeVocab = File(dir, "bpe.model").takeIf { it.isFile }?.absolutePath ?: "",
            modelingUnit = "bpe",
            numThreads = 4,
            provider = provider,
            modelType = "zipformer2",
        )
    }

    private inline fun createSpotter(
        config: KeywordSpotterConfig,
        build: () -> KeywordSpotter,
    ): KeywordSpotter =
        try {
            build()
        } catch (e: Exception) {
            Timber.e(e, "Sherpa KWS init failed (source=%s)", modelSource)
            throw IllegalStateException("Sherpa model failed to load: ${e.message}", e)
        }

    private fun writeKeywordsFile(work: File, content: String): File {
        if (!work.isDirectory && !work.mkdirs()) {
            throw IllegalStateException("Cannot create Sherpa work directory: $work")
        }
        val file = File(work, GENERATED_KEYWORDS_FILE_NAME)
        // Only rewrite when the content changed — avoids touching the file
        // (and re-triggering any engine caches) on identical rebuilds.
        if (!file.isFile || file.readText(Charsets.UTF_8) != content) {
            file.writeText(content, Charsets.UTF_8)
        }
        return file
    }

    /**
     * Map the native result's keyword text to one of OUR entries.
     * Comparison is whitespace-normalized AND space-stripped — the native
     * side has historically rendered the line's tokens both joined with
     * spaces and compact, and both must match the configured phrase.
     */
    private fun matchEntry(rawKeyword: String): SherpaKeywords.Entry? {
        val normalized = rawKeyword.trim()
        if (normalized.isEmpty()) return null
        val exact = normalized
        val compact = normalized.filterNot { it.isWhitespace() }
        return entries.firstOrNull { it.tokenLine.trim() == exact } ?: run {
            entries.firstOrNull { it.tokenLine.filterNot { ch -> ch.isWhitespace() } == compact }
        }
    }

    override fun process(chunk: ShortArray): Int {
        // 16-bit PCM → float in [-1, 1).
        val f = FloatArray(chunk.size) { chunk[it] / 32768.0f }
        stream.acceptWaveform(f, 16_000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
        }
        val r = spotter.getResult(stream)
        if (r.keyword.isEmpty()) return -1
        val matched = matchEntry(r.keyword)
        spotter.reset(stream)
        return matched?.let { entries.indexOf(it) } ?: -1
    }

    override fun release() {
        try {
            stream.release()
        } catch (e: Exception) {
            Timber.d(e, "Sherpa engine stream release error")
        }
        try {
            spotter.release()
        } catch (e: Exception) {
            Timber.d(e, "Sherpa engine spotter release error")
        }
    }

    companion object {
        private const val ASSET_ENCODER_INT8 =
            "sherpa_kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val GENERATED_KEYWORDS_FILE_NAME = "keywords_generated.txt"
    }
}
