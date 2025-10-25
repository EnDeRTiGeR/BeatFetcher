package com.example.youtubetomp3.service

import android.content.Context
import android.os.Environment
import android.util.Log
import android.media.MediaScannerConnection
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.common.util.UnstableApi

@Singleton
class AudioExtractor @Inject constructor(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    private var currentProcessId: String? = null
    

    @UnstableApi
    @Suppress("DEPRECATION")
    private suspend fun downloadAndConvertWithNewPipe(
        youtubeUrl: String,
        onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(youtubeUrl) ?: throw Exception("Invalid YouTube URL")
            Log.d("AudioExtractor", "Starting NewPipe extraction for: $videoId")
            
            onProgress(0.1f)
            
            // NewPipe is initialized via DI in AppModule (NewPipeLinkProcessor.ensureInitialized)

            // Extract stream info using latest API with comprehensive error handling and retry logic
            val streamInfo = try {
                Log.d("AudioExtractor", "Attempting StreamInfo.getInfo for: $youtubeUrl")
                
                // Implement retry logic with exponential backoff
                var retryCount = 0
                val maxRetries = 3
                var extractedStreamInfo: StreamInfo? = null
                
                while (retryCount <= maxRetries) {
                    try {
                        extractedStreamInfo = StreamInfo.getInfo(youtubeUrl)
                        Log.d("AudioExtractor", "NewPipe extraction successful on attempt ${retryCount + 1}")
                        break
                    } catch (e: java.net.MalformedURLException) {
                        Log.e("AudioExtractor", "NewPipe URL construction failed - YouTube API format may have changed: ${e.message}")
                        // This is likely a permanent failure due to YouTube changes, don't retry
                        throw Exception("YouTube API format has changed. NewPipe may need an update to handle the new format.")
                    } catch (e: NullPointerException) {
                        Log.e("AudioExtractor", "NewPipe encountered null data - YouTube response format may have changed: ${e.message}")
                        // This is likely a permanent failure due to YouTube changes, don't retry
                        throw Exception("YouTube response format has changed. NewPipe may need an update to handle the new format.")
                    } catch (e: ExtractionException) {
                        if (retryCount < maxRetries) {
                            Log.w("AudioExtractor", "NewPipe extraction exception on attempt ${retryCount + 1}, retrying... Error: ${e.message}")
                            // Longer delay for extraction issues
                            kotlinx.coroutines.delay((2000 * Math.pow(2.0, retryCount.toDouble())).toLong())
                            retryCount++
                        } else {
                            Log.e("AudioExtractor", "NewPipe extraction failed after $maxRetries retries: ${e.message}", e)
                            throw e
                        }
                    } catch (e: Exception) {
                        if (retryCount < maxRetries) {
                            Log.w("AudioExtractor", "NewPipe extraction failed on attempt ${retryCount + 1}, retrying... Error: ${e.message}")
                            // Exponential backoff: 1s, 2s, 4s delays
                            kotlinx.coroutines.delay((1000 * Math.pow(2.0, retryCount.toDouble())).toLong())
                            retryCount++
                        } else {
                            Log.e("AudioExtractor", "NewPipe extraction failed after $maxRetries retries: ${e.message}", e)
                            throw e
                        }
                    }
                }
                
                extractedStreamInfo ?: throw Exception("Failed to extract stream info after retries")
            } catch (e: NoSuchMethodError) {
                Log.w("AudioExtractor", "API levelcompatibility issue detected: ${e.message}")
                throw Exception("NewPipe API level compatibility issue - falling back to other methods")
            } catch (e: ExtractionException) {
                Log.w("AudioExtractor", "NewPipe extraction exception: ${e.message}")
                throw Exception("NewPipe extraction failed: ${e.message}")
            } catch (e: java.io.IOException) {
                Log.w("AudioExtractor", "NewPipe network error: ${e.message}")
                throw Exception("NewPipe network error - falling back to other methods")
            } catch (e: Exception) {
                Log.w("AudioExtractor", "NewPipe unexpected error: ${e.message}")
                throw Exception("NewPipe failed: ${e.message}")
            }
            
            onProgress(0.25f)
            Log.d("AudioExtractor", "NewPipe extraction successful for: ${streamInfo.name}")
            
            // Get best audio stream with comprehensive null safety
            val audioStreams = streamInfo.audioStreams
            Log.d("AudioExtractor", "Found ${audioStreams?.size ?: 0} audio streams")
            
            if (audioStreams.isNullOrEmpty()) {
                Log.e("AudioExtractor", "No audio streams available")
                return@withContext null
            }
            
            // Try to get the best quality audio stream with enhanced null safety
            val bestAudio = try {
                audioStreams
                    .filterNotNull() // Remove null streams first
                    .filter { stream -> 
                        // Validate stream has URL and is not a manifest URL
                        val url = stream.url
                        url?.isNotBlank() == true && 
                        !url.contains("manifest.googlevideo.com")
                    }
                    .maxByOrNull { stream -> 
                        // Prefer higher bitrate streams
                        stream.averageBitrate ?: 0
                    }
            } catch (e: Exception) {
                Log.w("AudioExtractor", "Error finding best audio stream: ${e.message}")
                // Fallback to first valid stream
                audioStreams.filterNotNull().firstOrNull { stream ->
                    // Validate stream has URL and is not a manifest URL
                    val url = stream.url
                    url?.isNotBlank() == true && 
                    !url.contains("manifest.googlevideo.com")
                }
            }
            
            if (bestAudio != null) {
                // Additional validation with comprehensive error handling
                val streamUrl = try {
                    bestAudio?.url?.takeIf { !it.isNullOrBlank() && it.startsWith("http") }
                } catch (e: Exception) {
                    Log.w("AudioExtractor", "Error accessing stream URL: ${e.message}")
                    null
                }
                
                if (!streamUrl.isNullOrEmpty()) {
                    Log.d("AudioExtractor", "Found audio stream: $streamUrl (bitrate: ${bestAudio.averageBitrate ?: "unknown"})")
                    
                    // Download the raw audio stream to temp file
                    val tempAudioFile = downloadRawAudioStream(
                        streamUrl = streamUrl,
                        videoId = videoId,
                        // Map raw 0..1 download progress -> 0.25..0.60
                        onProgress = { progress -> onProgress(0.25f + (progress * 0.35f)) }
                    )
                    
                    if (tempAudioFile != null) {
                        onProgress(0.60f)
                        Log.d("AudioExtractor", "Converting to M4A (AAC) using Media3 Transformer")

                        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                            ?: File(context.filesDir, "Music").apply { mkdirs() }
                        if (!downloadsDir.exists()) downloadsDir.mkdirs()
                        val m4aFile = File(downloadsDir, "$videoId.m4a")

                        // Build MediaItem from the downloaded temp audio file
                        val inputItem = MediaItem.fromUri(Uri.fromFile(tempAudioFile))

                        // Configure AAC in MP4 (M4A) output
                        val request = TransformationRequest.Builder()
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .build()

                        // Start the transformation on main thread and suspend until it completes (cancellable)
                        suspendCancellableCoroutine<Unit> { cont ->
                            val handler = Handler(Looper.getMainLooper())
                            var transformer: Transformer? = null
                            handler.post {
                                transformer = Transformer.Builder(context)
                                    .setTransformationRequest(request)
                                    .addListener(object : Transformer.Listener {
                                        override fun onCompleted(
                                            composition: Composition,
                                            exportResult: ExportResult
                                        ) {
                                            if (cont.isActive) cont.resume(Unit)
                                        }

                                        override fun onError(
                                            composition: Composition,
                                            exportResult: ExportResult,
                                            exportException: ExportException
                                        ) {
                                            if (cont.isActive) cont.resumeWithException(exportException)
                                        }
                                    })
                                    .build()
                                transformer!!.startTransformation(inputItem, m4aFile.absolutePath)
                            }
                            cont.invokeOnCancellation { transformer?.cancel() }
                        }
                        onProgress(0.70f)

                        // Clean up temp file
                        tempAudioFile.delete()

                        // Indicate finishing stage
                        onProgress(0.95f)

                        // Scan the output so it appears in music apps
                        try {
                            MediaScannerConnection.scanFile(
                                context,
                                arrayOf(m4aFile.absolutePath),
                                arrayOf("audio/mp4"),
                                null
                            )
                        } catch (_: Exception) { }

                        onProgress(1.0f)
                        return@withContext m4aFile.absolutePath
                    } else {
                        Log.e("AudioExtractor", "Failed to download audio stream")
                        return@withContext null
                    }
                } else {
                    Log.e("AudioExtractor", "Stream URL validation failed")
                    return@withContext null
                }
            } else {
                Log.e("AudioExtractor", "No audio streams found")
                return@withContext null
            }
            
        } catch (e: Exception) {
            Log.e("AudioExtractor", "NewPipe extraction failed: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Download raw audio stream to temporary file for local conversion
     */
    private suspend fun downloadRawAudioStream(
        streamUrl: String,
        videoId: String,
        onProgress: (Float) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "temp_audio")
        val tempFile = File(tempDir, "${videoId}_temp_audio")
        
        try {
            // Create temp directory for raw audio files
            if (!tempDir.exists()) {
                tempDir.mkdirs()
            }
            
            // Clean up any existing temp file
            if (tempFile.exists()) {
                tempFile.delete()
            }
            
            // Download the raw audio stream
            val request = Request.Builder()
                .url(streamUrl)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            
            val response = okHttpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("AudioExtractor", "HTTP error: ${response.code}")
                return@withContext null
            }
            
            val inputStream = response.body?.byteStream() ?: run {
                Log.e("AudioExtractor", "Response body is null")
                return@withContext null
            }
            
            val contentLength = response.body?.contentLength() ?: -1L
            var lastEmitTime = 0L
            var lastEmittedProgress = -1f
            
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var totalBytesRead = 0L
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        
                        // Report raw 0..1 download progress (caller maps to UI range)
                        if (contentLength > 0) {
                            val progress = (totalBytesRead.toFloat() / contentLength.toFloat())
                            val now = SystemClock.uptimeMillis()
                            val progressedEnough = lastEmittedProgress < 0f || (progress - lastEmittedProgress) >= 0.01f
                            val timeEnough = (now - lastEmitTime) >= 200
                            if (progressedEnough || timeEnough || progress >= 1f) {
                                onProgress(progress)
                                lastEmittedProgress = progress
                                lastEmitTime = now
                            }
                        }
                    }
                }
            }
            
            // Verify the file was created and has content
            if (!tempFile.exists() || tempFile.length() == 0L) {
                Log.e("AudioExtractor", "Downloaded file is empty or not created")
                return@withContext null
            }
            
            // Signal completion of download (raw)
            onProgress(1.0f)
            Log.d("AudioExtractor", "Raw audio downloaded: ${tempFile.absolutePath} (${tempFile.length()} bytes)")
            return@withContext tempFile
            
        } catch (e: Exception) {
            Log.e("AudioExtractor", "Raw audio download failed", e)
            // Clean up any partially downloaded file
            if (tempFile.exists()) {
                tempFile.delete()
            }
            return@withContext null
        }
    }

    @UnstableApi
    @Suppress("DEPRECATION")
    suspend fun downloadAndConvert(youtubeUrl: String, onProgress: (Float) -> Unit): String {
        Log.d("AudioExtractor", "Starting download with NewPipe Extractor (primary method)")
        
        // Try NewPipe Extractor first (most reliable and actively maintained)
        val result = downloadAndConvertWithNewPipe(youtubeUrl, onProgress)
        if (result != null) {
            return result
        }
        
        Log.e("AudioExtractor", "NewPipe path failed; no further fallbacks enabled")
        throw Exception("Extraction failed using NewPipe. Media3 is used for conversion only.")
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            Regex("(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/)([^&?/]+)"),
            Regex("youtube\\.com/watch\\?.*v=([^&]+)"),
            Regex("youtu\\.be/([^?]+)")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
} 