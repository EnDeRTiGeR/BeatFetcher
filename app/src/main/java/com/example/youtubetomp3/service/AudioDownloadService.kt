package com.example.youtubetomp3.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.util.Log
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import com.example.youtubetomp3.newpipe.NewPipeLinkProcessor
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.common.util.UnstableApi

 

@Singleton
class AudioDownloadService @Inject constructor(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val linkProcessor: NewPipeLinkProcessor
) {
    // Larger I/O buffer for faster network and disk throughput (default is 8 KiB)
    private val IO_BUFFER_SIZE: Int = 512 * 1024

    @UnstableApi
    @Suppress("DEPRECATION")
    suspend fun downloadMP3FromYouTube(
        youtubeUrl: String,
        title: String,
        onProgress: (Float) -> Unit,
        onByteProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val videoUrl = youtubeUrl
        try {
            // Normalize URL using LinkHandler (per NewPipe docs)
            val handler = linkProcessor.normalizeVideoUrl(videoUrl)
            val normalizedUrl = handler.url

            // Fetch stream info
            onProgress(0.1f)
            val info = run {
                var lastErr: Exception? = null
                var delayMs = 500L
                repeat(3) { attempt ->
                    try {
                        return@run linkProcessor.fetchStreamInfo(normalizedUrl)
                    } catch (e: Exception) {
                        lastErr = e
                        if (attempt < 2) {
                            Log.w("AudioDownloadService", "fetchStreamInfo retry ${attempt + 1}", e)
                            delay(delayMs)
                            delayMs = (delayMs * 2).coerceAtMost(2000L)
                        }
                    }
                }
                throw IOException("Failed to fetch stream info: ${'$'}{lastErr?.message}", lastErr)
            }

            // Pick best audio stream
            val audio = linkProcessor.pickBestAudioStream(info)
                ?: throw IOException("No audio streams available")
            onProgress(0.25f)

            val ext = "m4a" // Export AAC in MP4 (M4A) via Media3 Transformer
            val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "_")
            val audioUrl = audio.url ?: throw IOException("Audio stream URL is null")

            // Prepare output M4A file path
            val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir
            val outFile = File(outDir, "${safeTitle}.$ext")

            // 1) Download remote audio stream to a local temporary file with byte progress
            val tmpInput = File.createTempFile("yt_audio_", ".dat", context.cacheDir)
            try {
                val baseReqBuilder = okhttp3.Request.Builder()
                    .url(audioUrl)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari"
                    )
                    .header("Referer", "https://www.youtube.com/")
                    .header("Accept-Encoding", "identity")
                    .header("Connection", "keep-alive")

                // Probe for Range support and total size
                var total: Long? = null
                var rangeSupported = false
                val probeReq = baseReqBuilder
                    .header("Range", "bytes=0-0")
                    .get()
                    .build()
                httpClient.newCall(probeReq).execute().use { probeResp ->
                    if (probeResp.code == 206) {
                        val contentRange = probeResp.header("Content-Range") // e.g., bytes 0-0/123456
                        val acceptRanges = probeResp.header("Accept-Ranges")
                        if (!contentRange.isNullOrEmpty() && contentRange.contains('/')) {
                            val t = contentRange.substringAfterLast('/')
                                .toLongOrNull()
                            if (t != null && t > 0L) {
                                total = t
                                rangeSupported = true
                            }
                        }
                        if (!rangeSupported && acceptRanges?.equals("bytes", true) == true) {
                            val cl = probeResp.header("Content-Length")?.toLongOrNull()
                            if (cl != null && cl > 0L) { total = cl; rangeSupported = true }
                        }
                    } else if (probeResp.isSuccessful) {
                        val cl = probeResp.body?.contentLength()?.takeIf { it > 0L }
                        if (cl != null) total = cl
                    }
                }

                if (rangeSupported && total != null && total!! > 0L) {
                    // Parallel segmented download
                    val segmentCount = determineSegmentCount(total)
                    val partSize = total!! / segmentCount
                    val ranges = (0 until segmentCount).map { idx ->
                        val start = idx * partSize
                        val end = if (idx == segmentCount - 1) total!! - 1 else ((idx + 1) * partSize - 1)
                        start to end
                    }
                    val partFiles = ranges.map { File.createTempFile("yt_seg_${safeTitle}_", ".part", context.cacheDir) }
                    val downloadedAtomic = AtomicLong(0L)

                    var segmentedOk = false
                    try {
                        // Aggregate progress on a cadence while workers run
                        coroutineScope {
                            val aggregator = launch {
                                var lastEmit = System.nanoTime()
                                while (isActive) {
                                    val now = System.nanoTime()
                                    if (now - lastEmit > 100_000_000L) { // ~100ms
                                        val d = downloadedAtomic.get()
                                        onByteProgress(d, total)
                                        val frac = if (total!! > 0L) d.toFloat() / total!!.toFloat() else 0f
                                        onProgress(0.25f + 0.35f * frac.coerceIn(0f, 1f))
                                        lastEmit = now
                                    }
                                    delay(50)
                                }
                            }

                            val jobs = ranges.mapIndexed { i, (start, end) ->
                                async(Dispatchers.IO) {
                                    val req = baseReqBuilder
                                        .header("Range", "bytes=${start}-${end}")
                                        .get()
                                        .build()
                                    httpClient.newCall(req).execute().use { resp ->
                                        // Must be 206 for ranged segment
                                        if (resp.code != 206) throw IOException("Unexpected HTTP ${resp.code} for range segment ${i}")
                                        val body = resp.body ?: throw IOException("Empty body on segment ${i}")
                                        body.byteStream().use { input ->
                                            partFiles[i].outputStream().use { rawOut ->
                                                java.io.BufferedOutputStream(rawOut, IO_BUFFER_SIZE).use { output ->
                                                    val buffer = ByteArray(IO_BUFFER_SIZE)
                                                    while (true) {
                                                        val read = input.read(buffer)
                                                        if (read <= 0) break
                                                        output.write(buffer, 0, read)
                                                        downloadedAtomic.addAndGet(read.toLong())
                                                    }
                                                    output.flush()
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            try {
                                jobs.awaitAll()
                                segmentedOk = true
                            } finally {
                                aggregator.cancel()
                            }
                        }
                    } catch (segEx: Exception) {
                        // Cleanup parts and fall back to single-stream
                        try { partFiles.forEach { pf -> pf.delete() } } catch (_: Exception) {}
                        segmentedOk = false
                    }

                    if (segmentedOk) {
                        // Final emit after all segments complete
                        val finalDownloaded = downloadedAtomic.get()
                        onByteProgress(finalDownloaded, total)
                        val finalFrac = if (total!! > 0L) finalDownloaded.toFloat() / total!!.toFloat() else 1f
                        onProgress(0.25f + 0.35f * finalFrac.coerceIn(0f, 1f))

                        // Merge part files into the temp input in order
                        tmpInput.outputStream().use { rawOut ->
                            java.io.BufferedOutputStream(rawOut, IO_BUFFER_SIZE).use { output ->
                                partFiles.forEach { pf ->
                                    FileInputStream(pf).use { `in` ->
                                        `in`.copyTo(output, IO_BUFFER_SIZE)
                                    }
                                }
                                output.flush()
                            }
                        }
                        // Cleanup parts
                        partFiles.forEach { pf -> try { pf.delete() } catch (_: Exception) {} }
                    } else {
                        // Fallback: single-stream sequential download
                        val request = baseReqBuilder.get().build()
                        httpClient.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body ?: throw IOException("Empty body")
                            val tot = body.contentLength().takeIf { it > 0 } // may be -1 if unknown
                            var downloaded = 0L
                            body.byteStream().use { input ->
                                tmpInput.outputStream().use { rawOut ->
                                    java.io.BufferedOutputStream(rawOut, IO_BUFFER_SIZE).use { output ->
                                        val buffer = ByteArray(IO_BUFFER_SIZE)
                                        var lastEmit = System.nanoTime()
                                        while (true) {
                                            val read = input.read(buffer)
                                            if (read <= 0) break
                                            output.write(buffer, 0, read)
                                            downloaded += read

                                            // Emit byte progress occasionally
                                            val now = System.nanoTime()
                                            if (now - lastEmit > 100_000_000L) { // ~100ms
                                                onByteProgress(downloaded, tot)
                                                // Map byte fraction into 0.25 -> 0.60 range for overall progress
                                                val frac = if (tot != null && tot > 0) downloaded.toFloat() / tot.toFloat() else 0f
                                                onProgress(0.25f + 0.35f * frac.coerceIn(0f, 1f))
                                                lastEmit = now
                                            }
                                        }
                                        output.flush()
                                    }
                                }
                            }
                            // Final emit
                            onByteProgress(downloaded, tot)
                            val frac = if (tot != null && tot > 0) downloaded.toFloat() / tot.toFloat() else 1f
                            onProgress(0.25f + 0.35f * frac.coerceIn(0f, 1f))
                        }
                    }
                } else {
                    // Fallback: single-stream sequential download
                    val request = baseReqBuilder.get().build()
                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${'$'}{resp.code}")
                        val body = resp.body ?: throw IOException("Empty body")
                        val tot = body.contentLength().takeIf { it > 0 } // may be -1 if unknown
                        var downloaded = 0L
                        body.byteStream().use { input ->
                            tmpInput.outputStream().use { rawOut ->
                                java.io.BufferedOutputStream(rawOut, IO_BUFFER_SIZE).use { output ->
                                    val buffer = ByteArray(IO_BUFFER_SIZE)
                                    var lastEmit = System.nanoTime()
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read <= 0) break
                                        output.write(buffer, 0, read)
                                        downloaded += read

                                        // Emit byte progress occasionally
                                        val now = System.nanoTime()
                                        if (now - lastEmit > 100_000_000L) { // ~100ms
                                            onByteProgress(downloaded, tot)
                                            // Map byte fraction into 0.25 -> 0.60 range for overall progress
                                            val frac = if (tot != null && tot > 0) downloaded.toFloat() / tot.toFloat() else 0f
                                            onProgress(0.25f + 0.35f * frac.coerceIn(0f, 1f))
                                            lastEmit = now
                                        }
                                    }
                                    output.flush()
                                }
                            }
                        }
                        // Final emit
                        onByteProgress(downloaded, tot)
                        val frac = if (tot != null && tot > 0) downloaded.toFloat() / tot.toFloat() else 1f
                        onProgress(0.25f + 0.35f * frac.coerceIn(0f, 1f))
                    }
                }
            } catch (e: Exception) {
                try { tmpInput.delete() } catch (_: Exception) {}
                throw e
            }

            // 2) Build MediaItem from the downloaded temp file
            val inputItem = MediaItem.fromUri(Uri.fromFile(tmpInput))

            // 3) Configure transformation to AAC in MP4 (M4A)
            val request = TransformationRequest.Builder()
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()

            // 4) Start transformation on main thread and suspend until completion/errors.
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    var transformerRef: Transformer? = null
                    cont.invokeOnCancellation {
                        try {
                            transformerRef?.cancel()
                            releaseTransformer(transformerRef)
                            transformerRef = null
                        } catch (_: Exception) { }
                    }

                    Handler(Looper.getMainLooper()).post {
                        try {
                            val transformer = Transformer.Builder(context)
                                .setTransformationRequest(request)
                                .addListener(object : Transformer.Listener {
                                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                        try {
                                            releaseTransformer(transformerRef)
                                            transformerRef = null
                                        } catch (_: Exception) { }
                                        if (cont.isActive) cont.resume(Unit)
                                    }

                                    override fun onError(
                                        composition: Composition,
                                        exportResult: ExportResult,
                                        exportException: ExportException
                                    ) {
                                        try {
                                            releaseTransformer(transformerRef)
                                            transformerRef = null
                                        } catch (_: Exception) { }
                                        if (cont.isActive) {
                                            cont.resumeWithException(IOException("Transformation failed: ${'$'}{exportException.message}", exportException))
                                        }
                                    }
                                })
                                .build()
                            transformerRef = transformer
                            onProgress(0.6f)
                            transformer.startTransformation(inputItem, outFile.absolutePath)
                            onProgress(0.7f)
                        } catch (e: Exception) {
                            if (cont.isActive) cont.resumeWithException(e)
                        }
                    }
                }
            } finally {
                try { tmpInput.delete() } catch (_: Exception) {}
            }

            // After completion, validate output
            if (!outFile.exists() || outFile.length() == 0L) throw IOException("No output produced")
            onProgress(0.95f)

            // Insert into public Music via MediaStore so it appears in Music directory
            val resolver = context.contentResolver
            val displayName = "${safeTitle}.$ext"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // Save at top-level Music folder (no app subdirectory)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val mediaUri = resolver.insert(collection, values)
                ?: throw IOException("Failed to create MediaStore record")

            try {
                resolver.openOutputStream(mediaUri, "w")?.use { out ->
                    FileInputStream(outFile).use { input ->
                        input.copyTo(out, IO_BUFFER_SIZE)
                    }
                } ?: throw IOException("Failed to open MediaStore output stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalize = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(mediaUri, finalize, null, null)
                }
            } catch (e: Exception) {
                // Clean up partially inserted record on error
                try { resolver.delete(mediaUri, null, null) } catch (_: Exception) {}
                throw e
            } finally {
                // Remove temporary app-specific file
                try { outFile.delete() } catch (_: Exception) {}
            }

            onProgress(1.0f)
            return@withContext mediaUri.toString()
        } catch (e: ExtractionException) {
            throw IOException("Extraction failed: ${e.message}", e)
        } catch (e: Exception) {
            throw IOException("Failed to download MP3: ${e.message}", e)
        }
    }

    @UnstableApi
    @Suppress("DEPRECATION")
    suspend fun convertLocalVideoToAudio(
        inputUri: Uri,
        title: String = "local audio",
        onProgress: (Float) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        try {
            onProgress(0.1f)

            val ext = "m4a"
            val safeTitle = title.replace(Regex("[^A-Za-z0-9 _.-]"), "_")

            // Prepare temporary output in app-specific storage
            val outDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: context.filesDir
            val outFile = File(outDir, "${safeTitle}.${ext}")

            // 1) Copy source content to a temporary local file
            val tmpVideo = File.createTempFile("input_", ".mp4", context.cacheDir)
            try {
                context.contentResolver.openInputStream(inputUri)?.use { inp ->
                    tmpVideo.outputStream().use { out -> inp.copyTo(out) }
                } ?: throw IOException("Failed to open inputUri for reading")

                // Ensure fresh output
                if (outFile.exists()) outFile.delete()

                // 2) Build MediaItem from the temp file. With audio AAC output requested,
                //    Media3 will produce audio-only M4A.
                val inputItem = MediaItem.fromUri(Uri.fromFile(tmpVideo))

                // 3) Configure audio-only AAC in MP4 (M4A)
                val request = TransformationRequest.Builder()
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .build()

                // 4) Run Media3 Transformer on main thread
                suspendCancellableCoroutine<Unit> { cont ->
                    var transformerRef: Transformer? = null
                    try {
                        val handler = Handler(Looper.getMainLooper())
                        handler.post {
                            try {
                                val transformer = Transformer.Builder(context)
                                    .setTransformationRequest(request)
                                    .addListener(object : Transformer.Listener {
                                        override fun onCompleted(
                                            composition: Composition,
                                            exportResult: ExportResult
                                        ) {
                                            releaseTransformer(transformerRef)
                                            if (cont.isActive) cont.resume(Unit)
                                        }

                                        override fun onError(
                                            composition: Composition,
                                            exportResult: ExportResult,
                                            exportException: ExportException
                                        ) {
                                            releaseTransformer(transformerRef)
                                            if (cont.isActive) cont.resumeWithException(exportException)
                                        }
                                    })
                                    .build()
                                transformerRef = transformer
                                onProgress(0.6f)
                                transformer.startTransformation(inputItem, outFile.absolutePath)
                                onProgress(0.7f)
                            } catch (e: Exception) {
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }

            } finally {
                // 5) Delete temp video regardless of success
                try { tmpVideo.delete() } catch (_: Exception) {}
            }

            if (!outFile.exists() || outFile.length() == 0L) throw IOException("No output produced")
            onProgress(0.9f)

            // Insert into public Music via MediaStore
            val resolver = context.contentResolver
            val displayName = "${safeTitle}.${ext}"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }

            val mediaUri = resolver.insert(collection, values)
                ?: throw IOException("Failed to create MediaStore record")

            try {
                resolver.openOutputStream(mediaUri, "w")?.use { out ->
                    FileInputStream(outFile).use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IOException("Failed to open MediaStore output stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val finalize = ContentValues().apply {
                        put(MediaStore.Audio.Media.IS_PENDING, 0)
                    }
                    resolver.update(mediaUri, finalize, null, null)
                }
            } catch (e: Exception) {
                try { resolver.delete(mediaUri, null, null) } catch (_: Exception) {}
                throw e
            } finally {
                try { outFile.delete() } catch (_: Exception) {}
            }

            onProgress(1.0f)
            return@withContext mediaUri.toString()
        } catch (e: Exception) {
            throw IOException("Failed to convert local file: ${e.message}", e)
        }
    }

    // Attempt to call Transformer.release() if available in the linked Media3 version
    private fun releaseTransformer(transformer: Transformer?) {
        try {
            val t = transformer ?: return
            val method = t.javaClass.methods.firstOrNull { it.name == "release" && it.parameterCount == 0 }
            method?.invoke(t)
        } catch (_: Exception) {
            // Ignore; not available on this version
        }
    }

    fun deleteMP3File(filePath: String): Boolean {
        return try {
            val uri = try { Uri.parse(filePath) } catch (_: Exception) { null }
            if (uri != null && ("content".equals(uri.scheme, ignoreCase = true))) {
                context.contentResolver.delete(uri, null, null) > 0
            } else {
                File(filePath).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun determineSegmentCount(totalBytes: Long?): Int {
        // Save battery: single stream when on power saver or metered/cellular, or for small files
        if (isPowerSaveOn()) return 1
        if (isOnMeteredOrCellular()) return 1
        if (totalBytes != null && totalBytes < 10L * 1024L * 1024L) return 1 // <10MB
        return 2 // default lower parallelism than 3 to reduce CPU/wakelocks
    }

    private fun isOnMeteredOrCellular(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val nw = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(nw) ?: return false
                val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val metered = cm.isActiveNetworkMetered
                // Treat non-WiFi or metered as battery-sensitive
                (!wifi && cellular) || metered
            } else {
                @Suppress("DEPRECATION")
                cm.isActiveNetworkMetered
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isPowerSaveOn(): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isPowerSaveMode == true
    } catch (_: Exception) { false }
}