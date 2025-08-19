package com.example.youtubetomp3.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class DownloadItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val artist: String?,
    val duration: String?,
    val filePath: String,
    val fileSize: Long,
    val downloadDate: Long = System.currentTimeMillis(),
    val youtubeUrl: String,
    val thumbnailUrl: String?
) 