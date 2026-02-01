package com.example.youtubetomp3.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.youtubetomp3.R
import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.repository.DownloadRepository
import com.example.youtubetomp3.service.AudioPlayerService
import com.example.youtubetomp3.service.AudioDownloadService
import com.example.youtubetomp3.newpipe.NewPipeLinkProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.media.MediaMetadataRetriever
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.example.youtubetomp3.data.appDataStore
import android.provider.MediaStore
import android.content.ContentUris
import javax.inject.Inject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

 

@HiltViewModel
class MainViewModel @Inject constructor(
    private val downloadRepository: DownloadRepository,
    private val audioPlayerService: AudioPlayerService,
    private val audioDownloadService: AudioDownloadService,
    private val newPipeLinkProcessor: NewPipeLinkProcessor,
    private val httpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Emits newly shared URLs so the UI can react even when activity is already running
    private val _sharedUrlEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val sharedUrlEvents: SharedFlow<String> = _sharedUrlEvents.asSharedFlow()

    // Smoothing job for transform stage (0.70 -> ~0.90)
    private var transformSmoothingJob: Job? = null

    // Notification rate-limiting state
    private var notifyLastTs = 0L
    private var notifyLastPercent = -1
    private var notifyLastLabel = ""
    private var notifyInProgress = false

    // UI debouncing to limit Compose recompositions and main-thread work
    private var uiProgressLastTs = 0L
    private var uiProgressLastPercent = -1
    private var uiProgressLastLabel = ""
    private var byteUiLastTs = 0L
    private var byteUiLastBytes = 0L

    // Persist last playback info
    private val KEY_LAST_PATH = stringPreferencesKey("last_path")
    private val KEY_LAST_POS = longPreferencesKey("last_pos_ms")
    private var lastPersistedPath: String? = null
    private var lastPersistedPos: Long = -1L

    private val KEY_LIBRARY_MOODS_JSON = stringPreferencesKey("library_moods_json")

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "download_channel"
        private const val CHANNEL_NAME = "Downloads"
    }

    init {
        // Observe downloads via Room Flow to auto-update UI
        viewModelScope.launch {
            try {
                downloadRepository.getAllDownloadsFlow()
                    .catch { e ->
                        Log.e("MainViewModel", "Downloads flow error", e)
                        _uiState.update { it.copy(downloads = emptyList()) }
                    }
                    .collect { downloads ->
                        Log.d("MainViewModel", "Loaded ${downloads.size} downloads (flow)")
                        _uiState.update { it.copy(downloads = downloads) }
                    }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to collect downloads flow", e)
                _uiState.update { it.copy(downloads = emptyList()) }
            }
        }
        // Observe audio player state to drive UI play/pause and progress
        viewModelScope.launch {
            try {
                audioPlayerService.playerState.collect { ps ->
                    _uiState.update {
                        it.copy(
                            isPlaying = ps.isPlaying,
                            currentlyPlayingPath = ps.currentFilePath,
                            playbackPositionMs = ps.positionMs,
                            playbackDurationMs = ps.durationMs,
                            artworkData = ps.artworkData
                        )
                    }
                    // Persist last playback path/position occasionally
                    val path = ps.currentFilePath
                    val pos = ps.positionMs
                    val now = System.currentTimeMillis()
                    val shouldPersist = path != null && (
                        path != lastPersistedPath ||
                        kotlin.math.abs(pos - lastPersistedPos) >= 2000
                    )
                    if (shouldPersist) {
                        try {
                            context.appDataStore.edit { prefs ->
                                prefs[KEY_LAST_PATH] = path!!
                                prefs[KEY_LAST_POS] = pos
                            }
                            lastPersistedPath = path
                            lastPersistedPos = pos
                        } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Player state collection failed", e)
            }
        }
        // Load last saved playback info on startup
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = context.appDataStore.data.first()
                val p = prefs[KEY_LAST_PATH]
                val ms = prefs[KEY_LAST_POS] ?: 0L
                if (!p.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(currentlyPlayingPath = p, playbackPositionMs = ms) }
                        lastPersistedPath = p
                        lastPersistedPos = ms
                    }
                }
            } catch (_: Exception) { }
        }
        // Auto-advance when a track ends
        viewModelScope.launch {
            try {
                audioPlayerService.events.collect { ev ->
                    when (ev) {
                        is com.example.youtubetomp3.service.AudioPlayerService.PlayerEvent.TrackEnded -> {
                            // Prefer ExoPlayer's next when available; otherwise fall back to our computed queue.
                            playNext()
                        }
                        is com.example.youtubetomp3.service.AudioPlayerService.PlayerEvent.AutoClosed -> {
                            // Handle auto-closed event
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Player events collection failed", e)
            }
        }
        createNotificationChannel()
    }

    fun scanLibrary(force: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val alreadyLoaded = uiState.value.librarySongs.isNotEmpty() && !force
            if (alreadyLoaded) return@launch
            _uiState.update { it.copy(libraryLoading = true, libraryError = null) }
            try {
                val resolver = context.contentResolver
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val projection = arrayOf(
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.MIME_TYPE
                )
                val selection = "(${MediaStore.Audio.Media.IS_MUSIC}!=0) AND (${MediaStore.Audio.Media.MIME_TYPE} IN (?,?,?))"
                val selectionArgs = arrayOf("audio/mp4", "audio/m4a", "audio/mpeg")
                val sortOrder = MediaStore.Audio.Media.DATE_ADDED + " DESC"

                val items = mutableListOf<com.example.youtubetomp3.data.LibrarySong>()
                resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                    val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                    while (c.moveToNext()) {
                        val id = c.getLong(idCol)
                        val title = c.getString(titleCol) ?: "Unknown Title"
                        val artist = c.getString(artistCol)
                        val duration = c.getLong(durCol)
                        val contentUri = ContentUris.withAppendedId(collection, id)
                        items.add(com.example.youtubetomp3.data.LibrarySong(
                            title = title,
                            artist = artist,
                            durationMs = duration,
                            uri = contentUri.toString()
                        ))
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(librarySongs = items, libraryLoading = false, libraryError = null) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(libraryLoading = false, libraryError = e.message ?: "Failed to scan library") }
                }
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    fun convertToMP3(youtubeUrl: String) {
        // Prevent concurrent conversions
        if (uiState.value.isLoading) {
            Log.w("MainViewModel", "Conversion already in progress, ignoring YouTube request")
            return
        }
        Log.d("MainViewModel", "Starting download for URL: $youtubeUrl")
        viewModelScope.launch {
            _uiState.update { it.copy(
                isLoading = true,
                error = null,
                currentDownloadProgress = 0f,
                downloadedBytes = 0L,
                totalBytes = null,
                showByteProgress = false
            ) }
            
            // Show initial notification
            notifyInProgress = true
            updateProgressNotification("Starting download...", null, indeterminate = true, force = true)
            
            try {
                // Extract video ID from URL
                val videoId = extractVideoId(youtubeUrl)
                Log.d("MainViewModel", "Extracted video ID: $videoId")
                if (videoId == null) {
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = "Invalid YouTube URL. Please check the URL format."
                        ) 
                    }
                    completeNotification("Invalid YouTube URL")
                    return@launch
                }
                
                // Fetch basic metadata via NewPipe for naming (no YouTube Data API)
                Log.d("MainViewModel", "Fetching video metadata via NewPipe...")
                updateProgressNotification("Fetching video information...", null, indeterminate = true)
                val videoTitle = try {
                    val info = withContext(Dispatchers.IO) {
                        val handler = newPipeLinkProcessor.normalizeVideoUrl(youtubeUrl)
                        newPipeLinkProcessor.fetchStreamInfo(handler.url)
                    }
                    info.name ?: "YouTube Video ($videoId)"
                } catch (e: Exception) {
                    Log.w("MainViewModel", "NewPipe metadata fetch failed, using fallback", e)
                    "YouTube Video ($videoId)"
                }

                val thumbnailUrl = try {
                    val info = withContext(Dispatchers.IO) {
                        val handler = newPipeLinkProcessor.normalizeVideoUrl(youtubeUrl)
                        newPipeLinkProcessor.fetchStreamInfo(handler.url)
                    }
                    info.thumbnails?.firstOrNull()?.url
                } catch (_: Exception) {
                    null
                }
                Log.d("MainViewModel", "Video title: ${videoTitle}")
                updateProgressNotification("Preparing conversion", null, indeterminate = true)
                
                // Download via NewPipe + convert using Media3 Transformer (handled by MP3DownloadService)
                Log.d("MainViewModel", "Starting MP3 conversion...")
                val filePath = try {
                    audioDownloadService.downloadMP3FromYouTube(
                        youtubeUrl = youtubeUrl,
                        title = videoTitle,
                        onProgress = { progress ->
                            val p = progress.coerceIn(0f, 1f)
                            var label = ""
                            var indeterminate = false

                            when {
                                p < 0.25f -> {
                                    label = "Fetching info"
                                }
                                p < 0.60f -> {
                                    label = "Downloading audio"
                                }
                                p < 0.70f -> {
                                    label = "Preparing conversion"
                                }
                                p < 0.95f -> {
                                    label = "Converting"
                                    indeterminate = true

                                    // Start/maintain smoothing from current progress up to ~0.90
                                    if (transformSmoothingJob?.isActive != true) {
                                        transformSmoothingJob = viewModelScope.launch {
                                            var local = uiState.value.currentDownloadProgress
                                            if (local < 0.70f) local = 0.70f
                                            while (isActive) {
                                                delay(500)
                                                val current = uiState.value.currentDownloadProgress
                                                val target = 0.90f
                                                if (current >= 0.95f || !uiState.value.isLoading || !uiState.value.progressIndeterminate) break
                                                val next = (current + 0.01f).coerceAtMost(target)
                                                _uiState.update { it.copy(currentDownloadProgress = next) }
                                                if (next >= target) break
                                            }
                                        }
                                    }
                                }
                                p < 1.0f -> {
                                    label = "Finishing up"
                                }
                                else -> {
                                    label = "Completed"
                                }
                            }

                            // Cancel smoothing once we reach finishing or completion
                            if (p >= 0.95f) {
                                transformSmoothingJob?.cancel()
                                indeterminate = false
                            }

                            // Debounce UI recompositions (Compose state) to avoid main-thread jank
                            val percentIntLocal = (p * 100).toInt()
                            val nowTs = System.currentTimeMillis()
                            val shouldUiUpdate =
                                (nowTs - uiProgressLastTs) >= 250L ||
                                kotlin.math.abs(percentIntLocal - uiProgressLastPercent) >= 2 ||
                                (label != uiProgressLastLabel)

                            if (shouldUiUpdate) {
                                uiProgressLastTs = nowTs
                                uiProgressLastPercent = percentIntLocal
                                uiProgressLastLabel = label

                                _uiState.update { it.copy(
                                    currentDownloadProgress = if (p < 0.95f && it.progressIndeterminate) it.currentDownloadProgress else p,
                                    currentProgressLabel = label,
                                    progressIndeterminate = indeterminate,
                                    showByteProgress = if (p < 0.60f) it.showByteProgress else false
                                ) }

                                // Throttled progress notification (already self-throttled inside)
                                val percentInt = if (!indeterminate) (_uiState.value.currentDownloadProgress * 100).toInt() else null
                                val notifyLabel = if (_uiState.value.showByteProgress && _uiState.value.downloadedBytes > 0L) {
                                    val dl = _uiState.value.downloadedBytes
                                    val tot = _uiState.value.totalBytes
                                    val dlStr = formatMB(dl)
                                    val totStr = tot?.let { formatMB(it) }
                                    if (totStr != null) "$label ${dlStr} / ${totStr}" else "$label ${dlStr}"
                                } else label
                                updateProgressNotification(notifyLabel, percentInt, indeterminate)
                            }
                        }
                        ,
                        onByteProgress = byteProgress@ { downloaded, total ->
                            // Debounce byte-level UI updates to at most ~4/sec or when >=256 KiB delta
                            val now = System.currentTimeMillis()
                            val deltaTimeOk = (now - byteUiLastTs) >= 250L
                            val deltaBytesOk = kotlin.math.abs(downloaded - byteUiLastBytes) >= 256 * 1024
                            if (!(deltaTimeOk || deltaBytesOk)) return@byteProgress

                            byteUiLastTs = now
                            byteUiLastBytes = downloaded

                            _uiState.update { it.copy(
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                showByteProgress = true,
                                currentProgressLabel = "Downloading audio"
                            ) }

                            // Also reflect bytes in notification occasionally (percent is already driven by onProgress)
                            val percentInt = (_uiState.value.currentDownloadProgress * 100).toInt()
                            val dlStr = formatMB(downloaded)
                            val totStr = total?.let { formatMB(it) }
                            val lbl = if (totStr != null) "Downloading audio ${dlStr} / ${totStr}" else "Downloading audio ${dlStr}"
                            updateProgressNotification(lbl, percentInt, indeterminate = false)
                        }
                    )
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Download failed", e)
                    transformSmoothingJob?.cancel()
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = "Failed to download video: ${e.message}. Please try again.",
                            showByteProgress = false
                        ) 
                    }
                    completeNotification("Download failed: ${e.message}")
                    return@launch
                }
                
                Log.d("MainViewModel", "Download completed. File path: $filePath")
                
                // Create download item with real metadata
                val resolvedArtist = resolveArtist(filePath) ?: "Unknown Artist"
                val resolvedDuration = resolveDuration(filePath) ?: "Unknown"
                val download = DownloadItem(
                    title = videoTitle,
                    artist = resolvedArtist,
                    duration = resolvedDuration,
                    filePath = filePath,
                    fileSize = getFileSize(filePath),
                    youtubeUrl = youtubeUrl,
                    thumbnailUrl = thumbnailUrl
                )
                
                try {
                    Log.d("MainViewModel", "Saving to database...")
                    downloadRepository.insertDownload(download)
                    Log.d("MainViewModel", "Download saved successfully")
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to save download", e)
                    _uiState.update { 
                        it.copy(
                            isLoading = false, 
                            error = "Failed to save download info: ${e.message}"
                        ) 
                    }
                    completeNotification("Failed to save download info")
                    return@launch
                }
                
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        currentDownloadProgress = 0f,
                        currentProgressLabel = "",
                        progressIndeterminate = false,
                        error = null,
                        showByteProgress = false,
                        downloadedBytes = 0L,
                        totalBytes = null
                    ) 
                }
                
                completeNotification("Download completed: ${videoTitle}")
                // Clear notification after a delay
                kotlinx.coroutines.delay(3000)
                clearNotification()
                Log.d("MainViewModel", "Download process completed successfully")
                
            } catch (e: Exception) {
                Log.e("MainViewModel", "Convert to MP3 failed", e)
                transformSmoothingJob?.cancel()
                _uiState.update { 
                    it.copy(
                        isLoading = false, 
                        error = "Failed to convert video: ${e.message}. Please try again."
                    ) 
                }
                completeNotification("Download failed: ${e.message}")
            }
        }
    }

    @androidx.media3.common.util.UnstableApi
    fun convertLocalFile(uri: Uri) {
        // Prevent concurrent conversions
        if (uiState.value.isLoading) {
            Log.w("MainViewModel", "Conversion already in progress, ignoring local request")
            return
        }
        Log.d("MainViewModel", "Starting local conversion for uri: $uri")
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, currentDownloadProgress = 0f) }

            // Show initial notification
            notifyInProgress = true
            updateProgressNotification("Preparing conversion", null, indeterminate = true, force = true)

            try {
                val filePath = audioDownloadService.convertLocalVideoToAudio(
                    
                    inputUri = uri,
                    title = "local audio"
                ) { progress ->
                    val p = progress.coerceIn(0f, 1f)
                    var label = ""
                    var indeterminate = false

                    when {
                        p < 0.70f -> {
                            label = "Preparing conversion"
                            indeterminate = true
                        }
                        p < 0.95f -> {
                            label = "Converting"
                            indeterminate = true
                            if (transformSmoothingJob?.isActive != true) {
                                transformSmoothingJob = viewModelScope.launch {
                                    var local = uiState.value.currentDownloadProgress
                                    if (local < 0.70f) local = 0.70f
                                    while (isActive) {
                                        delay(500)
                                        val current = uiState.value.currentDownloadProgress
                                        val target = 0.90f
                                        if (current >= 0.95f || !uiState.value.isLoading || !uiState.value.progressIndeterminate) break
                                        val next = (current + 0.01f).coerceAtMost(target)
                                        _uiState.update { it.copy(currentDownloadProgress = next) }
                                        if (next >= target) break
                                    }
                                }
                            }
                        }
                        p < 1.0f -> {
                            label = "Finishing up"
                        }
                        else -> {
                            label = "Completed"
                        }
                    }

                    if (p >= 0.95f) {
                        transformSmoothingJob?.cancel()
                        indeterminate = false
                    }

                    _uiState.update {
                        it.copy(
                            currentDownloadProgress = if (p < 0.95f && it.progressIndeterminate) it.currentDownloadProgress else p,
                            currentProgressLabel = label,
                            progressIndeterminate = indeterminate
                        )
                    }

                    val percentInt = if (!indeterminate) (_uiState.value.currentDownloadProgress * 100).toInt() else null
                    updateProgressNotification(label, percentInt, indeterminate)
                }

                // Build download item
                val download = DownloadItem(
                    title = "local audio",
                    artist = resolveArtist(filePath) ?: "Local",
                    duration = resolveDuration(filePath) ?: "Unknown",
                    filePath = filePath,
                    fileSize = getFileSize(filePath),
                    youtubeUrl = "local://",
                    thumbnailUrl = null
                )

                try {
                    downloadRepository.insertDownload(download)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Failed to save local conversion", e)
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to save download info: ${e.message}")
                    }
                    completeNotification("Failed to save download info")
                    return@launch
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        currentDownloadProgress = 0f,
                        currentProgressLabel = "",
                        progressIndeterminate = false,
                        error = null
                    )
                }

                completeNotification("Saved: local audio")
                delay(3000)
                clearNotification()
                Log.d("MainViewModel", "Local conversion completed successfully")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Local convert failed", e)
                transformSmoothingJob?.cancel()
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to convert file: ${e.message}")
                }
                completeNotification("Conversion failed: ${e.message}")
            }
        }
    }

    fun playAudio(filePath: String) {
        Log.d("MainViewModel", "Playing audio: $filePath")
        viewModelScope.launch {
            try {
                // Resume only if the underlying player has the same item loaded.
                // After app relaunch, UI may restore `currentlyPlayingPath` but ExoPlayer has no media item prepared yet.
                val canResume = try {
                    val player = audioPlayerService.getPlayer()
                    val curUri = player?.currentMediaItem?.localConfiguration?.uri?.toString()
                    uiState.value.currentlyPlayingPath == filePath && !uiState.value.isPlaying && curUri == filePath
                } catch (_: Exception) {
                    false
                }

                if (canResume) {
                    Log.d("MainViewModel", "Resuming existing audio instead of re-queueing: $filePath")
                    audioPlayerService.resumeAudio()
                    // Re-apply preferences in case they changed while paused
                    audioPlayerService.setShuffle(uiState.value.shuffleEnabled)
                    audioPlayerService.setRepeatOne(uiState.value.repeatOne)
                    return@launch
                }

                val lib = uiState.value.librarySongs.map { it.uri }
                val downloads = uiState.value.downloads
                val isLibrary = lib.contains(filePath)

                // Mood-based queue for downloads: if the chosen track has a moodTag, prefer that mood first.
                val list = if (isLibrary) {
                    lib
                } else {
                    val picked = downloads.find { it.filePath == filePath }
                    val mood = picked?.moodTag?.takeIf { !it.isNullOrBlank() }
                    if (mood != null) {
                        val sameMood = downloads.filter { it.filePath != filePath && it.moodTag.equals(mood, ignoreCase = true) }
                        val other = downloads.filter { it.filePath != filePath && !it.moodTag.equals(mood, ignoreCase = true) }
                        listOf(filePath) + sameMood.map { it.filePath } + other.map { it.filePath }
                    } else {
                        downloads.map { it.filePath }
                    }
                }

                val idx = list.indexOf(filePath).let { if (it < 0) 0 else it }
                // Build full queue so notification prev/next work
                audioPlayerService.setQueue(list, idx)
                // Apply current shuffle/repeat preferences to player
                audioPlayerService.setShuffle(uiState.value.shuffleEnabled)
                audioPlayerService.setRepeatOne(uiState.value.repeatOne)

                // If this is an app-downloaded item, repair title metadata and fetch thumbnail artwork.
                val dlMatch = uiState.value.downloads.find { it.filePath == filePath }
                if (dlMatch != null) {
                    repairMediaStoreMetadataIfNeeded(dlMatch)
                    dlMatch.thumbnailUrl?.let { thumb ->
                        val bytes = fetchImageBytes(thumb)
                        if (bytes != null && bytes.isNotEmpty()) {
                            audioPlayerService.setCurrentArtwork(bytes)
                        }
                    }
                }
                Log.d("MainViewModel", "Audio queue set and playback started at index=$idx")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to play audio", e)
                _uiState.update { it.copy(error = "Failed to play audio: ${e.message}") }
            }
        }
    }

    fun playNextFor(filePath: String) {
        audioPlayerService.playNextInQueue(filePath)
    }

    fun addToQueue(filePath: String) {
        audioPlayerService.addToQueue(filePath)
    }

    fun updateDownloadMoodTag(downloadId: Long, moodTag: String?) {
        viewModelScope.launch {
            try {
                val dl = downloadRepository.getDownloadById(downloadId) ?: return@launch
                val normalized = moodTag?.trim()?.takeIf { it.isNotBlank() }
                downloadRepository.updateDownload(dl.copy(moodTag = normalized))
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to update mood tag", e)
                _uiState.update { it.copy(error = "Failed to update mood: ${e.message}") }
            }
        }
    }

    suspend fun getLibraryMoodTag(uri: String): String? {
        return try {
            val json = context.appDataStore.data.first()[KEY_LIBRARY_MOODS_JSON] ?: return null
            val obj = JSONObject(json)
            obj.optString(uri, "").trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    fun setLibraryMoodTag(uri: String, moodTag: String?) {
        val normalized = moodTag?.trim()?.takeIf { it.isNotBlank() }
        viewModelScope.launch {
            try {
                context.appDataStore.edit { prefs ->
                    val cur = prefs[KEY_LIBRARY_MOODS_JSON]
                    val obj = try { JSONObject(cur ?: "{}") } catch (_: Exception) { JSONObject() }
                    if (normalized == null) {
                        obj.remove(uri)
                    } else {
                        obj.put(uri, normalized)
                    }
                    prefs[KEY_LIBRARY_MOODS_JSON] = obj.toString()
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to set library mood tag", e)
                _uiState.update { it.copy(error = "Failed to update mood: ${e.message}") }
            }
        }
    }

    fun deleteLibrarySong(uri: String) {
        viewModelScope.launch {
            try {
                val parsed = try { Uri.parse(uri) } catch (_: Exception) { null }
                if (parsed == null) {
                    _uiState.update { it.copy(error = "Invalid track uri") }
                    return@launch
                }
                val deleted = withContext(Dispatchers.IO) {
                    try { context.contentResolver.delete(parsed, null, null) } catch (_: Exception) { 0 }
                }
                if (deleted <= 0) {
                    _uiState.update { it.copy(error = "Unable to delete track (permission denied or file not found)") }
                    return@launch
                }
                // Refresh library
                scanLibrary(force = true)
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete library song", e)
                _uiState.update { it.copy(error = "Failed to delete track: ${e.message}") }
            }
        }
    }

    private suspend fun fetchImageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun repairMediaStoreMetadataIfNeeded(download: DownloadItem) {
        val uri = try { Uri.parse(download.filePath) } catch (_: Exception) { null } ?: return
        if (!"content".equals(uri.scheme, ignoreCase = true)) return
        try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.Audio.Media.TITLE, download.title)
                if (!download.artist.isNullOrBlank()) put(MediaStore.Audio.Media.ARTIST, download.artist)
                put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            withContext(Dispatchers.IO) {
                context.contentResolver.update(uri, values, null, null)
            }
        } catch (_: Exception) {
        }
    }
    
    fun pauseAudio() {
        Log.d("MainViewModel", "Pausing audio")
        audioPlayerService.pauseAudio()
    }
    
    fun stopAudio() {
        Log.d("MainViewModel", "Stopping audio")
        audioPlayerService.stopAudio()
    }

    fun releasePlayer() {
        Log.d("MainViewModel", "Releasing player resources")
        audioPlayerService.release()
    }

    fun resumeLastPlayback() {
        val path = uiState.value.currentlyPlayingPath ?: return
        val pos = uiState.value.playbackPositionMs
        viewModelScope.launch {
            try {
                audioPlayerService.playAudio(path)
                if (pos > 0L) audioPlayerService.seekTo(pos)
            } catch (_: Exception) { }
        }
    }

    // --- Spotify-like controls ---
    fun toggleShuffle() {
        val newVal = !uiState.value.shuffleEnabled
        _uiState.update { it.copy(shuffleEnabled = newVal) }
        audioPlayerService.setShuffle(newVal)
    }

    fun toggleRepeatOne() {
        val newVal = !uiState.value.repeatOne
        _uiState.update { it.copy(repeatOne = newVal) }
        audioPlayerService.setRepeatOne(newVal)
    }

    private fun currentList(): List<String> {
        val state = uiState.value
        val cur = state.currentlyPlayingPath
        val lib = state.librarySongs
        return if (cur != null && lib.any { it.uri == cur }) {
            lib.map { it.uri }
        } else {
            state.downloads.map { it.filePath }
        }
    }

    private fun currentIndex(): Int? {
        val cur = uiState.value.currentlyPlayingPath ?: return null
        val list = currentList()
        val idx = list.indexOf(cur)
        return if (idx >= 0) idx else null
    }

    fun playIndex(index: Int) {
        val list = currentList()
        if (index in list.indices) {
            playAudio(list[index])
        }
    }

    fun playNext() {
        val player = try { audioPlayerService.getPlayer() } catch (_: Exception) { null }
        val hasNext = try { player?.hasNextMediaItem() == true } catch (_: Exception) { false }
        if (hasNext) {
            audioPlayerService.skipToNext()
            return
        }

        // Fallback: compute next index from our current list
        val list = currentList()
        val idx = currentIndex()
        Log.d("MainViewModel", "No next media item reported by player. Falling back. listSize=${list.size} curIdx=${idx}")
        if (list.isEmpty()) return
        val nextIdx = if (idx == null) 0 else (idx + 1).coerceAtMost(list.lastIndex)
        // If we are already at the end, restart at 0 (continuous playback)
        val target = if (idx != null && idx >= list.lastIndex) 0 else nextIdx
        playIndex(target)
    }

    fun playPrevious() {
        val player = try { audioPlayerService.getPlayer() } catch (_: Exception) { null }
        val hasPrev = try { player?.hasPreviousMediaItem() == true } catch (_: Exception) { false }
        if (hasPrev) {
            audioPlayerService.skipToPrevious()
            return
        }

        val list = currentList()
        val idx = currentIndex()
        Log.d("MainViewModel", "No previous media item reported by player. Falling back. listSize=${list.size} curIdx=${idx}")
        if (list.isEmpty()) return
        val target = if (idx == null) 0 else (idx - 1).coerceAtLeast(0)
        playIndex(target)
    }

    fun seekToPercent(percent: Float) {
        val dur = uiState.value.playbackDurationMs
        if (dur > 0L) {
            val p = percent.coerceIn(0f, 1f)
            val to = (dur * p).toLong()
            audioPlayerService.seekTo(to)
        }
    }

    fun deleteDownload(downloadId: Long) {
        Log.d("MainViewModel", "Deleting download: $downloadId")
        viewModelScope.launch {
            try {
                val download = downloadRepository.getDownloadById(downloadId)
                download?.let {
                    // Delete the actual MP3 file
                    audioDownloadService.deleteMP3File(it.filePath)
                    // Delete from database
                    downloadRepository.deleteDownload(downloadId)
                    Log.d("MainViewModel", "Download deleted successfully")
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete download", e)
                _uiState.update { it.copy(error = "Failed to delete download: ${e.message}") }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }


    // Call this when the user types or pastes a URL
    fun onUrlInputChanged(url: String) {
        val videoId = extractVideoId(url)
        Log.d("MainViewModel", "URL input changed: $url -> Video ID: $videoId")
        _uiState.update { it.copy(currentVideoId = videoId) }
    }

    // Call this when a URL is shared to the app
    fun onSharedUrlReceived(url: String) {
        val videoId = extractVideoId(url)
        Log.d("MainViewModel", "Shared URL received: $url -> Video ID: $videoId")
        _uiState.update { it.copy(currentVideoId = videoId) }
        viewModelScope.launch { _sharedUrlEvents.emit(url) }
    }
    
    // Simple video ID extraction without API
    private fun extractVideoId(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return null

        val idRegex = Regex("^[A-Za-z0-9_-]{11}$")
        fun normalizeCandidate(candidate: String?): String? {
            val c = candidate?.trim()?.takeIf { it.isNotBlank() } ?: return null
            return if (idRegex.matches(c)) c else null
        }

        // If user already pasted a plain videoId
        normalizeCandidate(trimmed)?.let { return it }

        // Try parsing as Uri first (more reliable than regex)
        val uri = try { Uri.parse(trimmed) } catch (_: Exception) { null }
        if (uri != null) {
            // https://www.youtube.com/watch?v=VIDEO_ID
            normalizeCandidate(uri.getQueryParameter("v"))?.let { return it }

            val host = (uri.host ?: "").lowercase()
            val pathSegments = uri.pathSegments ?: emptyList()

            // https://youtu.be/VIDEO_ID
            if (host.endsWith("youtu.be") && pathSegments.isNotEmpty()) {
                normalizeCandidate(pathSegments.firstOrNull())?.let { return it }
            }

            // https://www.youtube.com/shorts/VIDEO_ID
            val shortsIdx = pathSegments.indexOfFirst { it.equals("shorts", ignoreCase = true) }
            if (shortsIdx != -1 && pathSegments.size > shortsIdx + 1) {
                normalizeCandidate(pathSegments[shortsIdx + 1])?.let { return it }
            }

            // https://www.youtube.com/embed/VIDEO_ID
            val embedIdx = pathSegments.indexOfFirst { it.equals("embed", ignoreCase = true) }
            if (embedIdx != -1 && pathSegments.size > embedIdx + 1) {
                normalizeCandidate(pathSegments[embedIdx + 1])?.let { return it }
            }
        }

        // Fallback regexes (also covers music.youtube.com)
        val patterns = listOf(
            Regex("(?:youtube\\.com|music\\.youtube\\.com)/watch\\?.*?[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/shorts/([A-Za-z0-9_-]{11})"),
            Regex("youtube\\.com/embed/([A-Za-z0-9_-]{11})")
        )

        for (pattern in patterns) {
            val match = pattern.find(trimmed)
            if (match != null) {
                normalizeCandidate(match.groupValues[1])?.let { return it }
            }
        }

        return null
    }

    // Read duration (as mm:ss) and artist metadata from audio file/uri when available
    private fun resolveDuration(path: String): String? = try {
        val retriever = MediaMetadataRetriever()
        val uri = Uri.parse(path)
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            retriever.setDataSource(context, uri)
        } else {
            retriever.setDataSource(path)
        }
        val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        retriever.release()
        durMs?.let { ms ->
            val totalSec = (ms / 1000).toInt()
            val m = totalSec / 60
            val s = totalSec % 60
            String.format(java.util.Locale.US, "%d:%02d", m, s)
        }
    } catch (e: Exception) { null }

    private fun resolveArtist(path: String): String? = try {
        val retriever = MediaMetadataRetriever()
        val uri = Uri.parse(path)
        if ("content".equals(uri.scheme, ignoreCase = true)) {
            retriever.setDataSource(context, uri)
        } else {
            retriever.setDataSource(path)
        }
        val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_AUTHOR)
            ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
        retriever.release()
        artist
    } catch (e: Exception) { null }

    // Resolve size for both content URIs and file paths
    private fun getFileSize(path: String): Long {
        return try {
            val uri = Uri.parse(path)
            if ("content".equals(uri.scheme, ignoreCase = true)) {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (idx != -1 && c.moveToFirst()) c.getLong(idx) else 0L
                } ?: 0L
            } else {
                java.io.File(path).length()
            }
        } catch (e: Exception) { 0L }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = CHANNEL_NAME
            val descriptionText = "Channel for download notifications"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Throttled progress notification to avoid system muting for noisy updates
    private fun updateProgressNotification(label: String, percent: Int?, indeterminate: Boolean, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val p = percent?.coerceIn(0, 100)
        val shouldNotify = force ||
            (now - notifyLastTs) >= 1000L ||
            (p != null && kotlin.math.abs(p - notifyLastPercent) >= 2) ||
            (label != notifyLastLabel)

        if (!shouldNotify) return

        notifyLastTs = now
        if (p != null) notifyLastPercent = p
        notifyLastLabel = label

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("YouTube to MP3")
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(notifyInProgress)

        if (indeterminate) {
            builder.setContentText(label)
            builder.setProgress(0, 0, true)
        } else {
            val text = if (p != null) "$label: ${'$'}p%" else label
            builder.setContentText(text)
            if (p != null) builder.setProgress(100, p, false) else builder.setProgress(0, 0, true)
        }

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun formatMB(bytes: Long): String {
        val mb = bytes / 1024.0 / 1024.0
        return String.format(java.util.Locale.US, "%.2f MB", mb)
    }

    private fun completeNotification(message: String) {
        notifyInProgress = false
        notifyLastPercent = -1
        notifyLastLabel = ""
        notifyLastTs = 0L

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("BeatFetcher")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle("BeatFetcher")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun clearNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }
}
