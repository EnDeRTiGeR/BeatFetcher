package com.example.youtubetomp3.ui

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

private const val TAG = "YouTubePlayerPreview"

@Composable
fun YouTubePlayerPreview(
    videoId: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentVideoId = rememberSaveable { mutableStateOf(videoId) }
    // Keep a single instance of the player view to avoid re-initialization jank
    val playerView = remember(context) { YouTubePlayerView(context) }
    var listenerAdded by rememberSaveable { mutableStateOf(false) }
    
    // Reset the player when videoId changes
    LaunchedEffect(videoId) {
        currentVideoId.value = videoId
    }
    
    if (currentVideoId.value.isNullOrBlank()) return
    
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            playerView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                if (!listenerAdded) {
                    // Add player listener only once
                    addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            try {
                                currentVideoId.value?.let { id ->
                                    // Post cueVideo to next frame to reduce main-thread work spikes
                                    post {
                                        youTubePlayer.cueVideo(id, 0f)
                                        Log.d(TAG, "YouTube player ready for video: $id")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error initializing YouTube player", e)
                            }
                        }
                        
                        override fun onError(
                            youTubePlayer: YouTubePlayer,
                            error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
                        ) {
                            Log.e(TAG, "YouTube player error: $error")
                        }
                    })
                    listenerAdded = true
                }
            }
        },
        update = { _ ->
            // No-op for now
        }
    )

    // Attach/detach lifecycle observer and release the player to avoid leaks and extra work
    DisposableEffect(lifecycleOwner, playerView) {
        lifecycleOwner.lifecycle.addObserver(playerView)
        onDispose {
            try {
                lifecycleOwner.lifecycle.removeObserver(playerView)
            } catch (_: Exception) { }
            try {
                playerView.release()
            } catch (_: Exception) { }
        }
    }
}