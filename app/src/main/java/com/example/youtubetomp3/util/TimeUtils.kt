package com.example.youtubetomp3.util

fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = (ms / 1000).toInt()
    val m = totalSec / 60
    val s = totalSec % 60
    return String.format(java.util.Locale.US, "%d:%02d", m, s)
}
