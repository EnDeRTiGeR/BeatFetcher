package com.example.youtubetomp3.service

import android.app.Service
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Binder
import android.os.IBinder
import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.repository.DownloadRepository
import com.example.youtubetomp3.newpipe.NewPipeLinkProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DownloadService : Service() {
    
    @Inject
    lateinit var downloadRepository: DownloadRepository
    
    @Inject
    lateinit var audioDownloadService: AudioDownloadService
    
    @Inject
    lateinit var newPipeLinkProcessor: NewPipeLinkProcessor
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = DownloadBinder()
    private var mediaPlayer: MediaPlayer? = null
    
    inner class DownloadBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }
    
    override fun onCreate() {
        super.onCreate()
    }
    
    override fun onBind(intent: Intent): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        mediaPlayer?.release()
        mediaPlayer = null
    }
    
    @androidx.media3.common.util.UnstableApi
    suspend fun downloadMP3(youtubeUrl: String, onProgress: (Float) -> Unit) {
        try {
            // Resolve a reasonable title via NewPipe (no YouTube Data API)
            val videoTitle = try {
                val handler = newPipeLinkProcessor.normalizeVideoUrl(youtubeUrl)
                val info = newPipeLinkProcessor.fetchStreamInfo(handler.url)
                info.name ?: "YouTube Video"
            } catch (_: Exception) {
                "YouTube Video"
            }
            
            // Download and export audio using Media3 Transformer (WAV)
            val filePath = audioDownloadService.downloadMP3FromYouTube(
                youtubeUrl = youtubeUrl,
                title = videoTitle,
                onProgress = { progress -> onProgress(progress) }
            )
            
            // Save to database
            val downloadItem = DownloadItem(
                title = videoTitle,
                artist = "Unknown Artist",
                duration = "Unknown",
                filePath = filePath,
                fileSize = getFileSize(filePath),
                youtubeUrl = youtubeUrl,
                thumbnailUrl = null
            )
            
            downloadRepository.insertDownload(downloadItem)
        } catch (e: Exception) {
            throw e
        }
    }
    
    suspend fun getAllDownloads(): List<DownloadItem> {
        return downloadRepository.getAllDownloads()
    }
    
    suspend fun deleteDownload(downloadId: Long) {
        val download = downloadRepository.getDownloadById(downloadId)
        download?.let {
            // Delete file or content URI
            val uri = try { Uri.parse(it.filePath) } catch (_: Exception) { null }
            if (uri != null && ("content".equals(uri.scheme, ignoreCase = true))) {
                contentResolver.delete(uri, null, null)
            } else {
                java.io.File(it.filePath).delete()
            }
            // Delete from database
            downloadRepository.deleteDownload(downloadId)
        }
    }
    
    fun playAudio(filePath: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                val uri = try { Uri.parse(filePath) } catch (_: Exception) { null }
                if (uri != null && ("content".equals(uri.scheme, ignoreCase = true))) {
                    setDataSource(this@DownloadService, uri)
                } else {
                    setDataSource(filePath)
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to play audio", e)
        }
    }
    
    fun stopAudio() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
    }
    
    // Resolve size for both content URIs and file paths
    private fun getFileSize(path: String): Long {
        return try {
            val uri = Uri.parse(path)
            if ("content".equals(uri.scheme, ignoreCase = true)) {
                contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (idx != -1 && c.moveToFirst()) c.getLong(idx) else 0L
                } ?: 0L
            } else {
                java.io.File(path).length()
            }
        } catch (e: Exception) { 0L }
    }
}