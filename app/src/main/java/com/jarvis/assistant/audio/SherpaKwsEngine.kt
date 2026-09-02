package com.jarvis.assistant.audio

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import timber.log.Timber

/**
 * Fully on-device wake-word engine backed by Sherpa-ONNX Keyword Spotting
 * (zipformer2 transducer model, xnnpack CPU backend). No account, no network.
 *
 * The Sherpa-ONNX AAR (v1.13.6) only exposes the non-null
 * `KeywordSpotter(AssetManager, KeywordSpotterConfig)` constructor — there is
 * NO nullable-ctor overload, so `KeywordSpotter(config)` does NOT compile.
 * Therefore the model MUST be loaded from the bundled assets via RELATIVE
 * paths (Mode A): a non-null AssetManager given an absolute `filesDir` path
 * crashes natively (`AAssetManager_open` on an absolute path → fatal). We
 * never copy the model out of the APK, and never pass a directory here.
 *
 * The Sherpa [KeywordSpotter] requires a non-null Android [Context] to obtain
 * its [android.content.res.AssetManager], so the caller must supply one.
 *
 * [keyword] is accepted for API symmetry with the request but is unused: the
 * phrase is fixed and provided by the bundled `sherpa_kws/keywords.txt`.
 */
class SherpaKwsEngine(
    context: Context?,
    @Suppress("UNUSED_PARAMETER") keyword: String,
    sensitivity: Float,
) : WakeWordEngine {

    // M5: map the 0.0–1.0 [sensitivity] to Sherpa's `keywordsThreshold`.
    // Higher sensitivity → lower threshold → easier trigger.
    private val keywordsThreshold = 0.25f + (1f - sensitivity.coerceIn(0f, 1f)) * 0.5f

    private val spotter: KeywordSpotter
    private val stream: OnlineStream

    init {
        // H5: Guard against passing an absolute file path instead of a Context.
        // The Sherpa AAR loads models from APK assets via AssetManager using
        // relative paths. An absolute path triggers a native crash in
        // AAssetManager_open. Fail early with a clear message.
        require(context is Context) {
            "Sherpa-ONNX requires a non-null context for AssetManager. " +
                "Passing absolute file paths will crash natively."
        }

        val assetManager = requireNotNull(context?.assets) {
            "Context (for AssetManager) required to build Sherpa-ONNX KeywordSpotter"
        }

        // All paths are RELATIVE to the assets root (Mode A).
        val modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = "sherpa_kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
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
        val config = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = modelConfig,
            keywordsFile = "sherpa_kws/keywords.txt",
            keywordsScore = 1.5f,
            keywordsThreshold = keywordsThreshold,
            numTrailingBlanks = 2,
        )

        try {
            spotter = KeywordSpotter(assetManager, config)
        } catch (e: Exception) {
            Timber.e(e, "Sherpa KWS init failed")
            throw IllegalStateException("Sherpa model failed to load: ${e.message}", e)
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

    override fun process(chunk: ShortArray): Int {
        // 16-bit PCM → float in [-1, 1).
        val f = FloatArray(chunk.size) { chunk[it] / 32768.0f }
        stream.acceptWaveform(f, 16_000)
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
        }
        val r = spotter.getResult(stream)
        return if (r.keyword.isNotEmpty()) {
            spotter.reset(stream)
            0
        } else {
            -1
        }
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
}
