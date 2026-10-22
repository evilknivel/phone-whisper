package com.kafkasl.phonewhisper

import android.content.Context
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.*
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A single file of a model that is published as loose files instead of a tar.bz2 archive. */
data class ModelFile(val name: String, val size: Long, val sha256: String)

/**
 * [archive] is the install dir name under files/models/. Models without [files] are fetched as
 * `<archive>.tar.bz2` from the sherpa-onnx release; models with [files] download each file from
 * [baseUrl] and verify its SHA-256.
 */
data class Model(
    val name: String,
    val archive: String,
    val sizeMb: Int,
    val quality: String,
    val recommended: Boolean = false,
    val baseUrl: String? = null,
    val files: List<ModelFile> = emptyList(),
)

val MODEL_CATALOG = listOf(
    Model("Parakeet 110M", "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8",
        100, "★★★ Best value", recommended = true),
    Model("Whisper Base", "sherpa-onnx-whisper-base.en",
        199, "★★★"),
    Model("Parakeet 0.6B", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        465, "★★★★ Best quality"),
    Model("Parakeet Ultra", "sherpa-onnx-parakeet-ultra-int8",
        600, "★★★★★ Most accurate",
        baseUrl = "https://huggingface.co/mldecode/parakeet-ultra-onnx-int8/resolve/3282a6e32885b431c1543d58c7710e6e3412eac0",
        files = listOf(
            ModelFile("encoder.int8.onnx", 611_879_344, "181382735a719c75076d13658dc4418de4b566aef39935ca0f8f55da16928f4e"),
            ModelFile("decoder.int8.onnx", 12_257_685, "0ba8ace2de04bb2d9a6b20ed2d67138c23df4a385f268438df0ff200502de77a"),
            ModelFile("joiner.int8.onnx", 5_281_747, "20ae4350c2484ba607d94f08ef25ae3ead762d8aaf70753758ffcc504e255ebb"),
            ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )),
    Model("Moonshine Tiny", "sherpa-onnx-moonshine-tiny-en-int8",
        103, "★★☆ Fast"),
)


sealed class DownloadState {
    data class Downloading(val progress: Float) : DownloadState()
    object Extracting : DownloadState()
    object Done : DownloadState()
    data class Error(val message: String) : DownloadState()
}

/**
 * Downloads run on background threads and outlive the activity that started them. Their state is
 * kept here, so a new screen can show progress via [state] and [addListener], and a second
 * download of the same model is refused while one is running.
 */
object ModelDownloader {
    private const val BASE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    /** Written as the last step of an install; a model dir without it was interrupted. */
    const val INSTALLED_MARKER = ".installed"

    private val active = ConcurrentHashMap<String, DownloadState>()
    private val listeners = CopyOnWriteArrayList<(Model, DownloadState) -> Unit>()

    fun modelDir(ctx: Context, model: Model) =
        File(LocalTranscriber.modelsDir(ctx), model.archive)

    /** Installs from before the marker existed count if all model files are there. */
    fun isInstalled(ctx: Context, model: Model): Boolean {
        val dir = modelDir(ctx, model)
        return File(dir, INSTALLED_MARKER).exists() || (dir.isDirectory && LocalTranscriber.hasModelFiles(dir))
    }

    /** Current state of a running download, or null if none is running. */
    fun state(model: Model): DownloadState? = active[model.archive]

    /** Listeners are called on a background thread. */
    fun addListener(listener: (Model, DownloadState) -> Unit) { listeners += listener }
    fun removeListener(listener: (Model, DownloadState) -> Unit) { listeners -= listener }

    /** Start downloading and installing [model]. Returns false if it is already downloading. */
    fun download(ctx: Context, model: Model): Boolean {
        if (active.putIfAbsent(model.archive, DownloadState.Downloading(0f)) != null) return false
        val appCtx = ctx.applicationContext
        // Only report download progress when the whole percentage changes, not per 16 KB chunk.
        var lastPercent = -1
        val onState: (DownloadState) -> Unit = { state ->
            val percent = (state as? DownloadState.Downloading)?.let { (it.progress * 100).toInt() }
            if (percent == null || percent != lastPercent) {
                percent?.let { lastPercent = it }
                if (state is DownloadState.Downloading || state is DownloadState.Extracting) {
                    active[model.archive] = state
                }
                listeners.forEach { it(model, state) }
            }
        }
        Thread {
            val result = try {
                if (model.files.isEmpty()) downloadArchive(appCtx, model, onState)
                else downloadFiles(appCtx, model, onState)
                DownloadState.Done
            } catch (e: Exception) {
                DownloadState.Error(e.message ?: "Unknown error")
            }
            active.remove(model.archive)
            onState(result)
        }.start()
        return true
    }

    /** Delete an installed model. Refused (false) while it is downloading. */
    fun delete(ctx: Context, model: Model): Boolean {
        if (active.containsKey(model.archive)) return false
        return modelDir(ctx, model).deleteRecursively()
    }

    private fun stagingDir(ctx: Context, model: Model) =
        File(ctx.filesDir, "model-downloads/${model.archive}")

    private fun downloadArchive(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val url = "$BASE_URL/${model.archive}.tar.bz2"
        val tmpFile = File(ctx.cacheDir, "${model.archive}.tar.bz2")
        val staging = stagingDir(ctx, model)
        try {
            downloadFile(url, tmpFile) { done, total ->
                if (total > 0) onState(DownloadState.Downloading(done.toFloat() / total))
            }
            onState(DownloadState.Extracting)
            staging.deleteRecursively()
            extractTarBz2(tmpFile, staging)
            installArchive(staging, model.archive, modelDir(ctx, model))
        } finally {
            tmpFile.delete()
            staging.deleteRecursively()
        }
    }

    /**
     * Move the model dir extracted into [staging] (the archive's top-level dir [archive]) to
     * [target], marking it installed. The move is a rename, so [target] is either complete or absent.
     */
    fun installArchive(staging: File, archive: String, target: File) {
        val extracted = File(staging, archive).takeIf { it.isDirectory } ?: staging
        if (!LocalTranscriber.hasModelFiles(extracted)) throw IOException("Archive contains no usable model")
        File(extracted, INSTALLED_MARKER).createNewFile()
        moveIntoPlace(extracted, target)
    }

    /** Download loose files into a staging dir (outside models/) and move it into place once all hashes match. */
    private fun downloadFiles(ctx: Context, model: Model, onState: (DownloadState) -> Unit) {
        val baseUrl = requireNotNull(model.baseUrl) { "No download URL for ${model.name}" }
        val staging = stagingDir(ctx, model)
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val total = model.files.sumOf { it.size }
            var finished = 0L
            for (file in model.files) {
                val sha = downloadFile("$baseUrl/${file.name}", File(staging, file.name)) { done, _ ->
                    onState(DownloadState.Downloading((finished + done).toFloat() / total))
                }
                if (!sha.equals(file.sha256, ignoreCase = true))
                    throw IOException("Checksum mismatch for ${file.name}")
                finished += file.size
            }
            File(staging, INSTALLED_MARKER).createNewFile()
            moveIntoPlace(staging, modelDir(ctx, model))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun moveIntoPlace(source: File, target: File) {
        target.parentFile?.mkdirs()
        target.deleteRecursively()
        if (!source.renameTo(target)) throw IOException("Could not move model into place")
    }

    /** Download [url] to [dest], reporting (bytes so far, content length). Returns the SHA-256 hex of the body. */
    private fun downloadFile(url: String, dest: File, onProgress: (Long, Long) -> Unit): String {
        Http.client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            val total = body.contentLength()
            return body.byteStream().use { src ->
                FileOutputStream(dest).use { dst -> copyWithSha256(src, dst) { onProgress(it, total) } }
            }
        }
    }
    /** Copy [src] to [dst], reporting bytes copied so far. Returns the lowercase SHA-256 hex of the data. */
    fun copyWithSha256(src: InputStream, dst: OutputStream, onProgress: (Long) -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16384)
        var copied = 0L
        var n: Int
        while (src.read(buf).also { n = it } != -1) {
            dst.write(buf, 0, n)
            digest.update(buf, 0, n)
            copied += n
            onProgress(copied)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Extract tar.bz2 to outDir. Validates paths to prevent traversal. */
    fun extractTarBz2(archive: File, outDir: File) {
        outDir.mkdirs()
        val bzIn = BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)))
        TarArchiveInputStream(bzIn).use { tar ->
            generateSequence { tar.nextEntry }.forEach { entry ->
                val dest = File(outDir, entry.name)
                val root = outDir.canonicalPath
                require(dest.canonicalPath == root || dest.canonicalPath.startsWith(root + File.separator)) {
                    "Path traversal: ${entry.name}"
                }
                if (entry.isDirectory) dest.mkdirs()
                else {
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { tar.copyTo(it) }
                }
            }
        }
    }
}
