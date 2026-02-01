package com.example.youtubetomp3.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.OpenableColumns
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerNotificationManager
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Color
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.example.youtubetomp3.MainActivity
import com.example.youtubetomp3.util.AppearanceBridge

@AndroidEntryPoint
class PlaybackForegroundService : Service() {

    @Inject lateinit var audioPlayerService: AudioPlayerService

    private var playerNotificationManager: PlayerNotificationManager? = null
    private var placeholderCache: Bitmap? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        ensureChannel()

        val player = audioPlayerService.getPlayer()
        if (player == null) {
            stopSelf()
            return
        }

        val mgr = PlayerNotificationManager.Builder(this, NOTIFICATION_ID, CHANNEL_ID)
            .setMediaDescriptionAdapter(object : PlayerNotificationManager.MediaDescriptionAdapter {
                override fun getCurrentContentTitle(player: androidx.media3.common.Player): CharSequence {
                    val md = player.mediaMetadata
                    // Prefer explicit metadata title. If missing or looks like a numeric media id
                    // (common for content:// URIs), attempt to resolve a friendly display name
                    val title = md.title?.toString()
                    if (!title.isNullOrBlank()) {
                        // If title is purely numeric (e.g. media id) try to resolve a filename
                        if (title.matches(Regex("^\\d+$"))) {
                            val uri = player.currentMediaItem?.localConfiguration?.uri
                            val resolved = uri?.let { resolveDisplayName(it) }
                            if (!resolved.isNullOrBlank()) return resolved
                        }
                        return title
                    }

                    // No metadata title: try to resolve from current media item's URI
                    val uri = player.currentMediaItem?.localConfiguration?.uri
                    val resolved = uri?.let { resolveDisplayName(it) }
                    if (!resolved.isNullOrBlank()) return resolved
                    return "Playing"
                }
                override fun createCurrentContentIntent(player: androidx.media3.common.Player): PendingIntent? {
                    val intent = Intent(this@PlaybackForegroundService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
                    return PendingIntent.getActivity(this@PlaybackForegroundService, 0, intent, flags)
                }
                override fun getCurrentContentText(player: androidx.media3.common.Player): CharSequence? {
                    // Show buffering state prominently; fall back to artist name
                    if (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) return "Buffering..."
                    val md = player.mediaMetadata
                    return md.artist
                }
                override fun getCurrentLargeIcon(player: androidx.media3.common.Player, callback: PlayerNotificationManager.BitmapCallback): Bitmap? {
                    val md = player.mediaMetadata
                    val data = md.artworkData
                    return if (data != null) {
                        try { BitmapFactory.decodeByteArray(data, 0, data.size) } catch (_: Throwable) { getPlaceholderArtworkCached() }
                    } else {
                        getPlaceholderArtworkCached()
                    }
                }
            })
            .setSmallIconResourceId(com.example.youtubetomp3.R.mipmap.mp3_round)
            .setNotificationListener(object : PlayerNotificationManager.NotificationListener {
                override fun onNotificationPosted(notificationId: Int, notification: android.app.Notification, ongoing: Boolean) {
                    if (ongoing) {
                        startForeground(notificationId, notification)
                    } else {
                        stopForeground(STOP_FOREGROUND_DETACH)
                    }
                }
                override fun onNotificationCancelled(notificationId: Int, dismissedByUser: Boolean) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            })
            .build()

        // Session token wiring is optional; omit to avoid compatibility issues with different token types

        mgr.setPriority(NotificationCompat.PRIORITY_LOW)
        mgr.setUseRewindAction(false)
        mgr.setUseFastForwardAction(false)
        mgr.setUsePreviousAction(true)
        mgr.setUseNextAction(true)
        mgr.setPlayer(player)

        playerNotificationManager = mgr
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep running while playing; notification manager will call startForeground
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        playerNotificationManager?.setPlayer(null)
        playerNotificationManager = null
        stopForeground(STOP_FOREGROUND_DETACH)
        placeholderCache = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(ch)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "playback_channel"
        private const val NOTIFICATION_ID = 42
    }

    private fun getPlaceholderArtworkCached(size: Int = 256): Bitmap {
        val cached = placeholderCache
        if (cached != null) return cached
        val bmp = generatePlaceholderArtwork(size)
        placeholderCache = bmp
        return bmp
    }

    private fun generatePlaceholderArtwork(size: Int = 256): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        // Background gradient
        val c1 = AppearanceBridge.primaryArgb
        val c2 = AppearanceBridge.secondaryArgb
        val c3 = AppearanceBridge.surfaceArgb
        paint.shader = LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(c1, c2, c3),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        // Waveform bars
        paint.color = AppearanceBridge.onSurfaceArgb
        val bars = 12
        val gap = size / (bars * 2f)
        val barW = gap
        for (i in 0 until bars) {
            val cx = gap + i * (barW + gap)
            val phase = (i / bars.toFloat()) * Math.PI * 2
            val amp = 0.35f + 0.6f * ((Math.sin(phase) + 1.0) / 2.0).toFloat()
            val h = size * amp * 0.8f
            val top = (size - h) / 2f
            val bottom = top + h
            canvas.drawRoundRect(cx, top, cx + barW, bottom, barW / 2f, barW / 2f, paint)
        }
        return bmp
    }

    // Try to resolve a friendly display name for a content/file Uri. Returns null when
    // no suitable name could be determined.
    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            // Prefer content provider DISPLAY_NAME when available
            if (uri.scheme == "content") {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) {
                            val name = cursor.getString(idx)
                            if (!name.isNullOrBlank()) {
                                val stripped = name.substringBeforeLast('.')
                                if (!stripped.matches(Regex("^\\d+$"))) return stripped
                            }
                        }
                    }
                }
            }
            // Fallback to lastPathSegment (filename) without extension
            val seg = uri.lastPathSegment ?: return null
            val name = seg.substringAfterLast('/').substringBeforeLast('.')
            if (name.isBlank()) return null
            if (name.matches(Regex("^\\d+$"))) return null
            name
        } catch (_: Exception) {
            null
        }
    }
}
