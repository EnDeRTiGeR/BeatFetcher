package com.example.youtubetomp3.di

import android.content.Context
import com.example.youtubetomp3.BuildConfig
import com.example.youtubetomp3.data.AppDatabase
import com.example.youtubetomp3.data.DownloadDao
import android.util.Log
import com.example.youtubetomp3.repository.DownloadRepository
import com.example.youtubetomp3.service.AudioPlayerService
import com.example.youtubetomp3.service.AudioDownloadService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.Cache
import javax.inject.Singleton
import java.io.File
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.youtubetomp3.newpipe.NewPipeLinkProcessor
import com.example.youtubetomp3.newpipe.OkHttpNewPipeDownloader
import okhttp3.Dispatcher

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
        }
        
        return OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .header("Referer", "https://www.youtube.com/")
                    .build()
                val response = chain.proceed(newRequest)
                val contentEncoding = response.header("Content-Encoding")
                if (contentEncoding != null) {
                    Log.d("OkHttpClient", "Content-Encoding: $contentEncoding")
                }
                response
            }
            .cache(Cache(File(context.cacheDir, "http_cache"), 50L * 1024L * 1024L))
            .connectTimeout(java.time.Duration.ofSeconds(15))
            .readTimeout(java.time.Duration.ofSeconds(60))
            .writeTimeout(java.time.Duration.ofSeconds(60))
            .dispatcher(Dispatcher().apply {
                maxRequests = 10
                maxRequestsPerHost = 4
            })
            .connectionPool(okhttp3.ConnectionPool(2, 2, java.util.concurrent.TimeUnit.MINUTES))
            .build()
    }
    
    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getDatabase(context)
    }
    
    @Provides
    @Singleton
    fun provideDownloadDao(database: AppDatabase): DownloadDao {
        return database.downloadDao()
    }
    
    @Provides
    @Singleton
    fun provideDownloadRepository(downloadDao: DownloadDao): DownloadRepository {
        return DownloadRepository(downloadDao)
    }
    
    @Provides
    @Singleton
    fun provideAudioPlayerService(@ApplicationContext context: Context): AudioPlayerService {
        return AudioPlayerService(context)
    }
    
    @Provides
    @Singleton
    fun provideAudioDownloadService(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        newPipeLinkProcessor: NewPipeLinkProcessor
    ): AudioDownloadService {
        return AudioDownloadService(context, httpClient, newPipeLinkProcessor)
    }
    
    @Provides
    @Singleton
    fun provideNewPipeDownloader(httpClient: OkHttpClient): OkHttpNewPipeDownloader {
        return OkHttpNewPipeDownloader(httpClient)
    }
    
    @Provides
    @Singleton
    fun provideNewPipeLinkProcessor(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        newPipeDownloader: OkHttpNewPipeDownloader
    ): NewPipeLinkProcessor {
        // Ensure NewPipe is initialized with our OkHttp-backed downloader once
        NewPipeLinkProcessor.ensureInitialized(newPipeDownloader)
        return NewPipeLinkProcessor(context, httpClient)
    }
    
    

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("app_preferences") }
        )
    }
} 