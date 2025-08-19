package com.example.youtubetomp3.newpipe

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.LinkHandler
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NewPipe-based link processing + audio extraction pipeline.
 * - Uses LinkHandler/LinkHandlerFactory to normalize/validate YouTube URLs.
 * - Uses StreamInfo.getInfo() from NewPipe to resolve streams.
 * - Downloads the selected audio-only stream to cache.
 */
@Singleton
class NewPipeLinkProcessor @Inject constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "NewPipeLink"
        @Volatile private var initialized = false

        fun ensureInitialized(downloader: Downloader) {
            if (!initialized) {
                synchronized(this) {
                    if (!initialized) {
                        try {
                            NewPipe.init(downloader)
                            initialized = true
                            Log.d(TAG, "NewPipe initialized")
                        } catch (e: IllegalStateException) {
                            // Already initialized by someone else, ignore
                            initialized = true
                            Log.d(TAG, "NewPipe already initialized")
                        }
                    }
                }
            }
        }
    }

    /**
     * Normalize and validate a YouTube video URL using LinkHandler.
     */
    fun normalizeVideoUrl(originalUrl: String): LinkHandler {
        val factory = YoutubeStreamLinkHandlerFactory.getInstance()
        return factory.fromUrl(originalUrl)
    }

    /**
     * Fetch StreamInfo for a video URL (or normalized LinkHandler.url).
     */
    @Throws(ExtractionException::class)
    suspend fun fetchStreamInfo(videoUrlOrId: String): StreamInfo = withContext(Dispatchers.IO) {
        // StreamInfo.getInfo will handle either URL or ID with the service
        StreamInfo.getInfo(ServiceList.YouTube, videoUrlOrId)
    }

    /**
     * Pick the best audio-only stream from StreamInfo.
     */
    fun pickBestAudioStream(info: StreamInfo): AudioStream? {
        // Prefer highest bitrate audio stream
        val audioStreams = info.audioStreams
        if (audioStreams.isNullOrEmpty()) return null
        return audioStreams.maxByOrNull { it.bitrate }
    }

    /**
     * Download a given audio stream URL to a cache file. Returns the File on success.
     */
    suspend fun downloadAudioStream(url: String, suggestedFileName: String, onProgress: (Float) -> Unit = {}): File? =
        withContext(Dispatchers.IO) {
            val cacheDir = File(context.cacheDir, "newpipe_audio")
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val outFile = File(cacheDir, suggestedFileName)

            try {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari"
                    )
                    .header("Referer", "https://www.youtube.com/")
                    // Let OkHttp decide Accept-Encoding (adds gzip and transparently decodes)
                    .build()

                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "HTTP ${'$'}{resp.code} when downloading audio")
                        return@withContext null
                    }
                    val body = resp.body ?: return@withContext null
                    val total = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buf = ByteArray(8 * 1024)
                            var read: Int
                            var downloaded = 0L
                            while (input.read(buf).also { read = it } != -1) {
                                output.write(buf, 0, read)
                                downloaded += read
                                if (total > 0) onProgress(downloaded / total.toFloat())
                            }
                            output.flush()
                        }
                    }
                }

                if (!outFile.exists() || outFile.length() == 0L) {
                    Log.e(TAG, "Downloaded file missing or empty")
                    return@withContext null
                }
                onProgress(1f)
                outFile
            } catch (e: IOException) {
                Log.e(TAG, "Download error: ${e.message}", e)
                null
            }
        }
}

/**
 * Minimal OkHttp-backed Downloader for NewPipe.
 * - Avoids setting Accept-Encoding manually so OkHttp manages gzip.
 * - Adds YouTube-friendly headers.
 */
class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NPRequest): NPResponse {
        val url = request.url()
        val builder = Request.Builder().url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome Mobile Safari"
            )
            .header("Referer", "https://www.youtube.com/")
            // Let OkHttp manage compression; don't add br explicitly

        // Forward headers from NewPipe request (except UA)
        request.headers()?.forEach { (k, vlist) ->
            if (k != null && !k.equals("User-Agent", ignoreCase = true)) {
                vlist?.forEach { v -> if (v != null) builder.addHeader(k, v) }
            }
        }

        when (request.httpMethod() ?: "GET") {
            "GET" -> builder.get()
            "POST" -> builder.post((request.dataToSend() ?: ByteArray(0)).toRequestBody(null))
            else -> builder.method(request.httpMethod() ?: "GET", null)
        }

        val resp = client.newCall(builder.build()).execute()
        val bodyString = resp.body?.string() ?: ""
        val headers = resp.headers.toMultimap()
        val enc = resp.header("Content-Encoding")
        val finalUrl = resp.request.url.toString()
        Log.d("NewPipeDownloader", "${resp.code} ${finalUrl} enc=${enc}")
        return NPResponse(resp.code, null, headers, bodyString, finalUrl)
    }
}
