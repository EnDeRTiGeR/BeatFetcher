package com.example.youtubetomp3.data

data class LibrarySong(
    val title: String,
    val artist: String?,
    val durationMs: Long,
    val uri: String
)
