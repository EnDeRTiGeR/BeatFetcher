package com.example.youtubetomp3.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

private const val TAG = "YouTubePlayerPreview"

@Composable
fun YouTubePlayerPreview(
    videoId: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentVideoId = videoId?.trim()
    // Keep a single instance of the player view to avoid re-initialization jank
    val playerView = remember(context) { YouTubePlayerView(context) }
    var listenerAdded by remember(playerView) { mutableStateOf(false) }
    var youTubePlayerRef by remember(playerView) { mutableStateOf<YouTubePlayer?>(null) }
    var previewFailed by remember(playerView) { mutableStateOf(false) }
    var showWebPreview by remember(playerView) { mutableStateOf(false) }
    
    // Cue whenever the video changes and the player is already ready.
    LaunchedEffect(currentVideoId, youTubePlayerRef) {
        val id = currentVideoId
        val player = youTubePlayerRef
        if (!id.isNullOrBlank() && player != null) {
            try {
                previewFailed = false
                player.cueVideo(id, 0f)
                Log.d(TAG, "Cueing video: $id")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to cue video: $id", e)
            }
        }
    }

    if (currentVideoId.isNullOrBlank()) return

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { _ ->
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
                                    youTubePlayerRef = youTubePlayer
                                    previewFailed = false
                                    val id = currentVideoId
                                    // Post cueVideo to next frame to reduce main-thread work spikes
                                    post {
                                        youTubePlayer.cueVideo(id, 0f)
                                        Log.d(TAG, "YouTube player ready for video: $id")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error initializing YouTube player", e)
                                }
                            }

                            override fun onError(
                                youTubePlayer: YouTubePlayer,
                                error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError
                            ) {
                                previewFailed = true
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

        if (previewFailed && !currentVideoId.isNullOrBlank()) {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Preview unavailable",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                val url = "https://www.youtube.com/watch?v=$currentVideoId"
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Open YouTube")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { showWebPreview = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Web Preview")
                        }
                    }
                }
            }
        }
    }

    if (showWebPreview && !currentVideoId.isNullOrBlank()) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showWebPreview = false },
            title = { Text("Preview") },
            text = {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl("https://www.youtube.com/embed/$currentVideoId")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { showWebPreview = false }) { Text("Close") }
            }
        )
    }

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