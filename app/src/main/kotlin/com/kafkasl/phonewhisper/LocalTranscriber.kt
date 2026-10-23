package com.kafkasl.phonewhisper

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import java.io.File

/**
 * Local on-device transcription via sherpa-onnx.
 * Models are loaded from the app's files/models/ dir. Not thread-safe: callers must not
 * [release] while [transcribe] is running.
 */
class LocalTranscriber private constructor(private val recognizer: OfflineRecognizer) {

    /** Transcribe raw PCM float samples. Blocking — call from background thread. */
    fun transcribe(samples: FloatArray, sampleRate: Int = 16000): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    /** Free the native recognizer. A model like Parakeet Ultra holds several hundred MB. */
    fun release() = recognizer.release()

    companion object {
        private const val TAG = "LocalTranscriber"

        /** Default CPU threads for decoding: 4, or fewer on phones with fewer cores. */
        val DEFAULT_THREADS get() = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

        fun modelsDir(ctx: Context) = File(ctx.filesDir, "models")

        /** Model dir names under files/models/ that contain a recognizable model. */
        fun availableModels(ctx: Context): List<String> =
            modelsDir(ctx).listFiles()
                ?.filter { it.isDirectory && hasModelFiles(it) }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()

        fun hasModelFiles(dir: File) = detectModelConfig(dir, 1) != null

        /** The files the recognizer loads from [dir], or null if it holds no recognizable model. */
        fun usedFiles(dir: File): List<File>? {
            val c = detectModelConfig(dir, 1)?.modelConfig ?: return null
            return listOf(
                c.tokens,
                c.transducer.encoder, c.transducer.decoder, c.transducer.joiner,
                c.whisper.encoder, c.whisper.decoder,
                c.moonshine.preprocessor, c.moonshine.encoder, c.moonshine.uncachedDecoder, c.moonshine.cachedDecoder,
                c.nemo.model,
            ).filter { it.isNotEmpty() }.map(::File)
        }

        /** Create a LocalTranscriber for the given model directory. Returns null on failure. */
        fun create(modelDir: File, numThreads: Int): LocalTranscriber? {
            if (!modelDir.isDirectory) {
                Log.e(TAG, "Model dir not found: $modelDir")
                return null
            }

            val config = detectModelConfig(modelDir, numThreads) ?: run {
                Log.e(TAG, "Could not detect model type in $modelDir")
                return null
            }

            return try {
                val recognizer = OfflineRecognizer(assetManager = null, config = config)
                Log.i(TAG, "Loaded model: ${modelDir.name} ($numThreads threads)")
                LocalTranscriber(recognizer)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load model: ${e.message}")
                null
            }
        }

        /** Auto-detect model type from files present in the directory. */
        fun detectModelConfig(dir: File, numThreads: Int): OfflineRecognizerConfig? {
            val p = dir.absolutePath
            val tokens = findTokens(dir) ?: return null

            // Moonshine (has preprocess.onnx)
            if (File("$p/preprocess.onnx").exists()) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        moonshine = OfflineMoonshineModelConfig(
                            preprocessor = "$p/preprocess.onnx",
                            encoder = findFile(p, "encode") ?: return null,
                            uncachedDecoder = findFile(p, "uncached_decode") ?: return null,
                            cachedDecoder = findFile(p, "cached_decode") ?: return null,
                        ),
                        tokens = tokens,
                        numThreads = numThreads,
                    )
                )
            }

            // Whisper (has encoder + decoder, no joiner)
            val encoder = findFile(p, "encoder")
            val decoder = findFile(p, "decoder")
            val joiner = findFile(p, "joiner")
            if (encoder != null && decoder != null && joiner == null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        whisper = OfflineWhisperModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                        ),
                        tokens = tokens,
                        numThreads = numThreads,
                        modelType = "whisper",
                    )
                )
            }

            // NeMo transducer / Parakeet TDT (has encoder + decoder + joiner)
            if (encoder != null && decoder != null && joiner != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = encoder,
                            decoder = decoder,
                            joiner = joiner,
                        ),
                        tokens = tokens,
                        numThreads = numThreads,
                        modelType = "nemo_transducer",
                    )
                )
            }

            // NeMo CTC (single model.onnx / model.int8.onnx)
            val ctcModel = findFile(p, "model")
            if (ctcModel != null) {
                return OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = ctcModel),
                        tokens = tokens,
                        numThreads = numThreads,
                    )
                )
            }

            return null
        }

        /** `tokens.txt`, or `<name>-tokens.txt` as in the sherpa-onnx Whisper archives. */
        private fun findTokens(dir: File): String? =
            dir.listFiles()
                ?.filter { it.name == "tokens.txt" || it.name.endsWith("-tokens.txt") }
                ?.minByOrNull { it.name.length }
                ?.absolutePath

        /**
         * Find first .onnx/.ort file named `<part>...` or `<name>-<part>...` (the Whisper archives
         * use `base.en-encoder.int8.onnx`), preferring int8 quantized files.
         */
        private fun findFile(dir: String, part: String): String? {
            val files = File(dir).listFiles()
                ?.filter { (it.name.startsWith(part) || it.name.contains("-$part")) &&
                    (it.name.endsWith(".onnx") || it.name.endsWith(".ort")) }
                ?.sortedBy { it.name }
                ?: return null
            return (files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull())?.absolutePath
        }
    }
}
