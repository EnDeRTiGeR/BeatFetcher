package com.example.youtubetomp3.service

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.AudioAttributes
import androidx.media3.session.MediaSession
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
                    setMediaItem(MediaItem.fromUri(uri))
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
    
    private fun startPositionUpdates() {
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (isActive) {
                val player = exoPlayer
                if (player != null) {
                    _playerState.value = _playerState.value.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        durationMs = player.duration.takeIf { it > 0 } ?: _playerState.value.durationMs,
                        isPlaying = player.isPlaying,
                        isLoading = player.playbackState == Player.STATE_BUFFERING
                    )
                }
                delay(500)
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
            _playerState.value = _playerState.value.copy(
                isPlaying = player.isPlaying,
                isLoading = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = player.duration.takeIf { it > 0 } ?: _playerState.value.durationMs
            )
        }
    }

    data class PlayerState(
        val isPlaying: Boolean = false,
        val isLoading: Boolean = false,
        val currentFilePath: String? = null,
        val error: String? = null,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L
    )
}