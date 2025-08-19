package com.example.youtubetomp3.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadDate DESC")
    fun getAllDownloads(): List<DownloadItem>
    
    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getDownloadById(id: Long): DownloadItem?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertDownload(download: DownloadItem): Long
    
    @Update
    fun updateDownload(download: DownloadItem)
    
    @Query("DELETE FROM downloads WHERE id = :id")
    fun deleteDownload(id: Long)
    
    @Query("SELECT * FROM downloads ORDER BY downloadDate DESC")
    fun getAllDownloadsFlow(): Flow<List<DownloadItem>>
} 