package com.example.youtubetomp3.repository

import com.example.youtubetomp3.data.DownloadItem
import com.example.youtubetomp3.data.DownloadDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val downloadDao: DownloadDao
) {
    fun getAllDownloadsFlow(): Flow<List<DownloadItem>> = downloadDao.getAllDownloadsFlow()

    suspend fun getAllDownloads(): List<DownloadItem> {
        return withContext(Dispatchers.IO) {
            downloadDao.getAllDownloads()
        }
    }
    
    suspend fun getDownloadById(id: Long): DownloadItem? {
        return withContext(Dispatchers.IO) {
            downloadDao.getDownloadById(id)
        }
    }
    
    suspend fun insertDownload(download: DownloadItem): Long {
        return withContext(Dispatchers.IO) {
            downloadDao.insertDownload(download)
        }
    }
    
    suspend fun deleteDownload(id: Long) {
        withContext(Dispatchers.IO) {
            downloadDao.deleteDownload(id)
        }
    }
    
    suspend fun updateDownload(download: DownloadItem) {
        withContext(Dispatchers.IO) {
            downloadDao.updateDownload(download)
        }
    }
}