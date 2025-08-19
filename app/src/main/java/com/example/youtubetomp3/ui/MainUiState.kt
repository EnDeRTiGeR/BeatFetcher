package com.example.youtubetomp3.ui

import com.example.youtubetomp3.data.DownloadItem

data class MainUiState(
    val isLoading: Boolean = false,
    val downloads: List<DownloadItem> = emptyList(),
    val error: String? = null,
    val currentDownloadProgress: Float = 0f,
    val currentProgressLabel: String = "",
    val progressIndeterminate: Boolean = false,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long? = null,
    val showByteProgress: Boolean = false,
    val isPlaying: Boolean = false,
    val currentlyPlayingPath: String? = null,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val currentVideoId: String? = null // For YouTube preview
)