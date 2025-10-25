package com.example.youtubetomp3.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.session.MediaSession
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaMetadata
import android.media.MediaMetadataRetriever
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
    
    private val _playerState = MutableStateFlow(PlayerState())
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    private val _events = MutableSharedFlow<PlayerEvent>(extraBufferCapacity = 1)
    val events: SharedFlow<PlayerEvent> = _events
    
    init {
        initializePlayer()
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
                    if (isPlaying) startPositionUpdates() else stopPositionUpdates()
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
            val uri = Uri.parse(filePath)
            exoPlayer?.apply {
                val currentItem = currentMediaItem
                val isSameItem = currentItem?.localConfiguration?.uri == uri
                if (!isSameItem) {
                    // New item or first play: replace, reset position, and prepare
                    stop()
                    // Extract rich metadata from tags
                    var title: String? = null
                    var artist: String? = null
                    var album: String? = null
                    var artData: ByteArray? = null
                    try {
                        val mmr = MediaMetadataRetriever()
                        mmr.setDataSource(context, uri)
                        title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                        artData = mmr.embeddedPicture
                        mmr.release()
                    } catch (_: Exception) { }

                    val displayTitle = title ?: uri.lastPathSegment ?: "Audio"
                    val mdBuilder = MediaMetadata.Builder()
                        .setTitle(displayTitle)
                        .setArtist(artist)
                        .setAlbumTitle(album)
                    if (artData != null) {
                        mdBuilder.setArtworkData(artData, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    }
                    val item = MediaItem.Builder()
                        .setUri(uri)
                        .setMediaMetadata(mdBuilder.build())
                        .build()
                    setMediaItem(item)
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
            
            _playerState.value = _playerState.value.copy(
                currentFilePath = filePath,
                error = null
            )
            updateStateFromPlayer()
            startPositionUpdates()

            // Ensure foreground service for media playback is running
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackForegroundService::class.java)
                )
            } catch (_: Exception) { }
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
    }
    
    fun resumeAudio() {
        exoPlayer?.let { player ->
            if (lastKnownPosition > 0L) player.seekTo(lastKnownPosition)
            player.play()
        }
        updateStateFromPlayer()
        startPositionUpdates()
    }
    
    fun stopAudio() {
        exoPlayer?.stop()
        lastKnownPosition = 0L
        _playerState.value = _playerState.value.copy(
            currentFilePath = null,
            isPlaying = false
        )
        stopPositionUpdates()
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
        mediaSession?.release()
        mediaSession = null
        exoPlayer?.release()
        exoPlayer = null
        stopPositionUpdates()
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
            lastKnownPosition = 0L
            // Ensure foreground playback notification is shown for queue playback
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PlaybackForegroundService::class.java)
                )
            } catch (_: Exception) { }
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

    private fun buildBasicMediaItem(uri: Uri): MediaItem {
        val title = uri.lastPathSegment ?: "Audio"
        val md = MediaMetadata.Builder()
            .setTitle(title)
            .build()
        return MediaItem.Builder().setUri(uri).setMediaMetadata(md).build()
    }

    private fun buildRichMediaItem(uri: Uri): MediaItem {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var artData: ByteArray? = null
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(context, uri)
            title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
            artData = mmr.embeddedPicture
            mmr.release()
        } catch (_: Exception) { }
        val displayTitle = title ?: uri.lastPathSegment ?: "Audio"
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
}