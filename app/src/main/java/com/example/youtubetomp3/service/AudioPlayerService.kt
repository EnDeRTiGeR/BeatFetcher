package com.example.youtubetomp3.service

import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.session.MediaSession
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaMetadata
import android.media.MediaMetadataRetriever
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlayerService @Inject constructor(
    private val context: Context
) {
    
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var lastKnownPosition: Long = 0L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var positionJob: Job? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    private var wasPlayingBeforeBluetoothDisconnect = false
    private var autoCloseJob: Job? = null
    private var pausedDueToBluetoothDisconnect = false
    
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlayerEvent> = _events
    
    companion object {
        private const val TAG = "AudioPlayerService"
        private const val AUTO_CLOSE_DELAY_MS = 5 * 60 * 1000L // 5 minutes
    }
    
    init {
        initializePlayer()
        registerBluetoothReceiver()
    }
    
    private fun initializePlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                .build()
            setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    updateStateFromPlayer()
                    if (exoPlayer?.isPlaying == true) startPositionUpdates() else stopPositionUpdates()
                    if (playbackState == Player.STATE_ENDED) {
                        _events.tryEmit(PlayerEvent.TrackEnded)
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    updateStateFromPlayer()
                    ensureRichMetadataForCurrent()
                }
                
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateStateFromPlayer()
                    if (isPlaying) {
                        // Playback resumed - cancel auto-close timer
                        cancelAutoCloseTimer()
                        pausedDueToBluetoothDisconnect = false
                        startPositionUpdates()
                    } else {
                        stopPositionUpdates()
                        // When playback stops, save position in case it was paused due to audio focus loss
                        // (e.g., Bluetooth disconnection). This allows resuming from the same position.
                        exoPlayer?.let { player ->
                            if (player.currentPosition > 0L) {
                                lastKnownPosition = player.currentPosition
                            }
                        }
                    }
                }
                
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _playerState.value = _playerState.value.copy(
                        error = error.message ?: "Playback error occurred"
                    )
                }
            })
        }
        exoPlayer?.let { player ->
            mediaSession = MediaSession.Builder(context, player).build()
        }
    }
    
    fun playAudio(filePath: String) {
        try {
            // Cancel auto-close timer when user starts playing
            cancelAutoCloseTimer()
            pausedDueToBluetoothDisconnect = false
            
            val uri = Uri.parse(filePath)
            exoPlayer?.apply {
                val currentItem = currentMediaItem
                val isSameItem = currentItem?.localConfiguration?.uri == uri
                if (!isSameItem) {
                    // New item or first play: replace, reset position, and prepare
                    stop()
                    // Set a MediaItem immediately so MediaSession/Bluetooth has title metadata.
                    val item = buildRichMediaItem(uri)
                    setMediaItem(item, /* startPositionMs= */ 0)
                    prepare()
                    lastKnownPosition = 0L
                } else {
                    // If previously stopped or ended, player is IDLE/ENDED and needs prepare again
                    if (playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
                        prepare()
                    }
                    if (lastKnownPosition > 0L) {
                        seekTo(lastKnownPosition)
                    }
                }
                play()
            }
            startPlaybackServiceIfNeeded()
            
            _playerState.value = _playerState.value.copy(
                currentFilePath = filePath,
                error = null
            )
            updateStateFromPlayer()
            startPositionUpdates()
        } catch (e: Exception) {
            _playerState.value = _playerState.value.copy(
                error = "Failed to play audio: ${e.message}"
            )
        }
    }
    
    fun pauseAudio() {
        exoPlayer?.let { player ->
            lastKnownPosition = player.currentPosition
            player.pause()
        }
        updateStateFromPlayer()
        stopPositionUpdates()
        
        // If paused due to Bluetooth disconnect, start auto-close timer
        if (pausedDueToBluetoothDisconnect) {
            startAutoCloseTimer()
        } else {
            // User manually paused - cancel any existing auto-close timer
            cancelAutoCloseTimer()
        }
    }
    
    fun resumeAudio() {
        // Cancel auto-close timer when user resumes playback
        cancelAutoCloseTimer()
        pausedDueToBluetoothDisconnect = false
        
        exoPlayer?.let { player ->
            if (lastKnownPosition > 0L) player.seekTo(lastKnownPosition)
            player.play()
        }
        startPlaybackServiceIfNeeded()
        updateStateFromPlayer()
        startPositionUpdates()
    }
    
    fun stopAudio() {
        cancelAutoCloseTimer()
        pausedDueToBluetoothDisconnect = false
        
        exoPlayer?.stop()
        lastKnownPosition = 0L
        // Release MediaSession so the system media controls/notification disappear
        mediaSession?.release()
        mediaSession = null
        _playerState.value = _playerState.value.copy(
            currentFilePath = null,
            isPlaying = false
        )
        stopPositionUpdates()
        stopPlaybackService()
    }
    
    fun seekTo(position: Long) {
        exoPlayer?.seekTo(position)
        updateStateFromPlayer()
    }
    
    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0L
    }
    
    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0L
    }
    
    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying ?: false
    }
    
    fun release() {
        cancelAutoCloseTimer()
        unregisterBluetoothReceiver()
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        stopPositionUpdates()
        stopPlaybackService()
    }
    
    /**
     * Register BroadcastReceiver to detect audio output disconnection
     * (Bluetooth, wired headphones, etc.) and pause playback automatically
     */
    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                    // This is triggered when audio output becomes unavailable
                    // (e.g., Bluetooth disconnects, headphones unplugged, audio route changes)
                    Log.d(TAG, "Audio output disconnected - pausing playback")
                    val player = exoPlayer
                    if (player != null && player.isPlaying) {
                        wasPlayingBeforeBluetoothDisconnect = true
                        pausedDueToBluetoothDisconnect = true
                        Log.d(TAG, "Pausing playback due to audio output disconnection")
                        pauseAudio()
                        // Auto-close timer will be started by pauseAudio() since pausedDueToBluetoothDisconnect is true
                    }
                }
            }
        }
        
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        
        try {
            context.registerReceiver(bluetoothReceiver, filter)
            Log.d(TAG, "Audio output receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register audio output receiver", e)
        }
    }
    
    /**
     * Start auto-close timer when paused due to Bluetooth disconnect
     * After 5 minutes of inactivity, release resources to save battery
     */
    private fun startAutoCloseTimer() {
        // Cancel any existing timer first
        cancelAutoCloseTimer()
        
        autoCloseJob = scope.launch {
            try {
                Log.d(TAG, "Auto-close timer started (5 minutes)")
                delay(AUTO_CLOSE_DELAY_MS)
                
                // Check if still paused and due to Bluetooth disconnect
                val player = exoPlayer
                if (player != null && !player.isPlaying && pausedDueToBluetoothDisconnect) {
                    Log.d(TAG, "Auto-closing app after 5 minutes of inactivity")
                    
                    // Release player resources to save battery
                    stopAudio()
                    
                    // Emit event to notify ViewModel/UI if needed
                    _events.tryEmit(PlayerEvent.AutoClosed)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in auto-close timer", e)
            }
        }
    }
    
    /**
     * Cancel the auto-close timer
     */
    private fun cancelAutoCloseTimer() {
        autoCloseJob?.cancel()
        autoCloseJob = null
    }
    
    private fun unregisterBluetoothReceiver() {
        bluetoothReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Bluetooth receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister Bluetooth receiver", e)
            }
            bluetoothReceiver = null
        }
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
    fun getMediaSession(): MediaSession? = mediaSession
    
    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null) {
                    val newPos = player.currentPosition.coerceAtLeast(0L)
                    val newDur = player.duration.takeIf { it > 0 } ?: _playerState.value.durationMs
                    val newIsPlaying = player.isPlaying
                    val newIsLoading = player.playbackState == Player.STATE_BUFFERING
                    val prev = _playerState.value
                    val posThreshold = if (newIsPlaying) 250L else 750L
                    val posChanged = kotlin.math.abs(newPos - prev.positionMs) >= posThreshold
                    if (posChanged ||
                        newDur != prev.durationMs ||
                        newIsPlaying != prev.isPlaying ||
                        newIsLoading != prev.isLoading) {
                        _playerState.value = prev.copy(
                            positionMs = newPos,
                            durationMs = newDur,
                            isPlaying = newIsPlaying,
                            isLoading = newIsLoading
                        )
                    }
                }
                delay(750)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun updateStateFromPlayer() {
        val player = exoPlayer
        if (player != null) {
            val newPos = player.currentPosition.coerceAtLeast(0L)
            val newDur = player.duration.takeIf { it > 0 } ?: _playerState.value.durationMs
            val newIsPlaying = player.isPlaying
            val newIsLoading = player.playbackState == Player.STATE_BUFFERING
            val newPath = player.currentMediaItem?.localConfiguration?.uri?.toString()
            val newArt = player.mediaMetadata.artworkData
            val prev = _playerState.value
            if (newPos != prev.positionMs || newDur != prev.durationMs || newIsPlaying != prev.isPlaying || newIsLoading != prev.isLoading || newPath != prev.currentFilePath || !java.util.Arrays.equals(newArt, prev.artworkData)) {
                _playerState.value = prev.copy(
                    isPlaying = newIsPlaying,
                    isLoading = newIsLoading,
                    positionMs = newPos,
                    durationMs = newDur,
                    currentFilePath = newPath,
                    artworkData = newArt
                )
            }
        }
    }

    // --- Queue/Playlist controls ---
    fun setQueue(paths: List<String>, startIndex: Int) {
        val player = exoPlayer ?: return
        try {
            val items = paths.mapIndexed { index, p ->
                val uri = Uri.parse(p)
                if (index == startIndex) buildRichMediaItem(uri) else buildBasicMediaItem(uri)
            }
            player.stop()
            player.clearMediaItems()
            player.setMediaItems(items, startIndex, /*startPositionMs=*/0)
            player.prepare()
            player.play()
            startPlaybackServiceIfNeeded()
        } catch (_: Exception) { }
    }

    fun setShuffle(enabled: Boolean) {
        exoPlayer?.shuffleModeEnabled = enabled
    }

    fun setRepeatOne(enabled: Boolean) {
        exoPlayer?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    fun skipToNext() { exoPlayer?.seekToNextMediaItem() }
    fun skipToPrevious() { exoPlayer?.seekToPreviousMediaItem() }

    fun addToQueue(filePath: String) {
        val player = exoPlayer ?: return
        try {
            val uri = Uri.parse(filePath)
            player.addMediaItem(buildBasicMediaItem(uri))
        } catch (_: Exception) { }
    }

    fun playNextInQueue(filePath: String) {
        val player = exoPlayer ?: return
        try {
            val uri = Uri.parse(filePath)
            val idx = (player.currentMediaItemIndex + 1).coerceAtLeast(0)
            player.addMediaItem(idx, buildBasicMediaItem(uri))
        } catch (_: Exception) { }
    }

    fun setCurrentArtwork(artworkData: ByteArray?) {
        val player = exoPlayer ?: return
        val idx = player.currentMediaItemIndex
        if (idx < 0) return
        val cur = player.currentMediaItem ?: return
        val uri = cur.localConfiguration?.uri ?: return
        val md = cur.mediaMetadata
        val builder = MediaMetadata.Builder()
            .setTitle(md.title)
            .setArtist(md.artist)
            .setAlbumTitle(md.albumTitle)

        if (artworkData != null) {
            builder.setArtworkData(artworkData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }

        try {
            player.replaceMediaItem(idx, MediaItem.Builder().setUri(uri).setMediaMetadata(builder.build()).build())
        } catch (_: Exception) { }
    }

    private fun buildBasicMediaItem(uri: Uri): MediaItem {
        val title = uri.lastPathSegment ?: "Audio"
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .build()
        return MediaItem.Builder().setUri(uri).setMediaMetadata(md).build()
    }

    private data class MediaStoreMeta(
        val title: String?,
        val artist: String?,
        val album: String?,
        val displayName: String?
    )

    private fun queryMediaStoreMeta(uri: Uri): MediaStoreMeta? {
        if (!"content".equals(uri.scheme, ignoreCase = true)) return null
        return try {
            val proj = arrayOf(
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DISPLAY_NAME
            )
            context.contentResolver.query(uri, proj, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                fun get(idx: Int): String? = if (idx >= 0) c.getString(idx) else null
                val title = get(c.getColumnIndex(MediaStore.Audio.Media.TITLE))
                val artist = get(c.getColumnIndex(MediaStore.Audio.Media.ARTIST))
                val album = get(c.getColumnIndex(MediaStore.Audio.Media.ALBUM))
                val displayName = get(c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME))
                MediaStoreMeta(title = title, artist = artist, album = album, displayName = displayName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildRichMediaItem(uri: Uri): MediaItem {
        // Prefer MediaStore fields for content:// URIs (Bluetooth/car head units often rely on these)
        val ms = queryMediaStoreMeta(uri)
        var title: String? = ms?.title
        var artist: String? = ms?.artist
        var album: String? = ms?.album
        var artData: ByteArray? = null
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            // Only override if MediaStore didn't provide values
            if (title.isNullOrBlank()) title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            if (artist.isNullOrBlank()) artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            if (album.isNullOrBlank()) album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            artData = mmr.embeddedPicture
            mmr.release()
        } catch (_: Exception) { }

        val displayTitle = when {
            !title.isNullOrBlank() -> title
            !ms?.displayName.isNullOrBlank() -> ms?.displayName
            else -> uri.lastPathSegment ?: "Audio"
        }
        val mdBuilder = MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(artist)
            .setAlbumTitle(album)
        if (artData != null) mdBuilder.setArtworkData(artData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        return MediaItem.Builder().setUri(uri).setMediaMetadata(mdBuilder.build()).build()
    }

    data class PlayerState(
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val currentFilePath: String? = null,
        val error: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val artworkData: ByteArray? = null
    )

    sealed class PlayerEvent {
        object TrackEnded : PlayerEvent()
        object AutoClosed : PlayerEvent()
    }

    private fun ensureRichMetadataForCurrent() {
        val player = exoPlayer ?: return
        val idx = player.currentMediaItemIndex
        if (idx < 0) return
        val cur = player.currentMediaItem ?: return
        val hasArt = cur.mediaMetadata.artworkData != null
        val hasArtist = cur.mediaMetadata.artist != null
        if (hasArt && hasArtist) return
        val uri = cur.localConfiguration?.uri ?: return
        try {
            val rich = buildRichMediaItem(uri)
            player.replaceMediaItem(idx, rich)
        } catch (_: Exception) { }
    }

    private fun startPlaybackServiceIfNeeded() {
        try {
            val intent = Intent(context, PlaybackForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) { }
    }

    private fun stopPlaybackService() {
        try {
            val intent = Intent(context, PlaybackForegroundService::class.java)
            context.stopService(intent)
        } catch (_: Exception) { }
    }
}