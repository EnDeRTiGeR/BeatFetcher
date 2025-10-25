package com.example.youtubetomp3.ui

import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.data.LibrarySong

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
    val artworkData: ByteArray? = null,
    val currentVideoId: String? = null,
    val shuffleEnabled: Boolean = false,
    val repeatOne: Boolean = false,
    val librarySongs: List<LibrarySong> = emptyList(),
    val libraryLoading: Boolean = false,
    val libraryError: String? = null
)